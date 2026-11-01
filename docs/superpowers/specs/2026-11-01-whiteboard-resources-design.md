# L1.5 白板与答题资源接入设计

状态：作者已确认（2026-11-01）；依据原任务、配额更正及设计确认。分支 `feat/whiteboard-resources`，基线 main `f0d7f85`。

## 已确认的产品边界

- 以练习验收清单为准。作答图片、提交截图、白板均不计入存储配额；上传和白板保存不调用 QuotaService / PracticeStorage。
- 白板为可编辑的作答草稿层与只追加的笔记层。单题提交不锁草稿层；整轮完成才锁草稿层。完成练习、历史页面和垃圾回收本次不实现。
- 提交仍先保存答案、事务外判分、事务内乐观锁落库；草稿与提交现有回执重查语义不变。
- Java 只鉴权资源，文件经 Nginx internal / X-Accel-Redirect 传输。

## 实现路径与取舍

现有写队列对所有动作使用答案 revision，白板表没有 revision。白板若沿用答案 revision，必须同时写作答状态行，违反本次高频写约束。因此采用白板独立 revision，答案 revision 不变；前端队列按动作选择版本。另一种把白板并入作答状态 JSON 的方案同样违反单行写约束，不采用。

以下为实现建议，非额外产品规则：

1. 新增 Flyway V7，只向白板表增加笔记层 JSON 与 revision，既有 draft 保留为草稿层。读题返回两层、白板 revision 和草稿是否锁定；保留兼容旧前端的 draft 字段。
2. 白板写仍为 `POST Q/actions` 的 `action=whiteboard`，`data.draft` 是当前层的笔迹文档，增加 `data.layer` 区分草稿与笔记，默认草稿。请求 revision 为白板版本。服务端只定位当前用户、当前题目与当前轮次，处理该白板行；不投影、不查询整本题库、不更新答案状态或汇总版本。
3. 草稿层可替换；笔记层只能保留已有笔迹并追加，不能修改、删除既有笔迹。两层合计 UTF-8 JSON 不超过 2 MiB。已完成轮次拒绝草稿层写入。前端提供草稿 / 笔记切换，锁定内容可见但不能擦除。
4. 独立白板 revision 也参与前端重读判断，避免答案版本未变时错误命中旧白板。白板保存响应只更新白板相关状态，不能覆盖答案缓存、答案 revision 或尚未保存的答题区内容。
5. 回执与白板行在同一事务中落库。相同 requestId 重放返回原响应；不同内容返回 400 REQUEST_ID_REUSED；旧白板 revision 返回 409 REVISION_CONFLICT。并发首次保存与更新分别测试。事务冲突后在新事务重查回执。

## 图片上传与访问

- 保持现有前端请求：`POST /api/resources` JSON `{mime,data}`（data 为 base64），返回 `{id,mime,size}`。mime 从 PNG / JPEG / WebP 文件头识别，不以声明 MIME、文件名为依据；GIF 不在本任务允许的上传类型内。
- 单文件 4 MiB：沿用旧版 `host/resources/resources.js:MAX_IMAGE_BYTES` 和前端 bridge 的限制。先限制请求体和 base64 长度，再解码，避免超大请求无限分配内存；超限返回 413 IMAGE_SIZE_LIMIT。
- 统一按用户限制上传频率，建议每分钟 30 次；通过可注入 Clock 的内存限流器验证跨窗口及并发，超过返回 429 RESOURCE_RATE_LIMIT。当前为单 app，后续多实例需共享限流状态。
- 单题当前作答内的不同图片引用（包括答题区截图）最多 20 个，在保存草稿、提交前统一递归检查；不依赖选项或题型结构。历史中已冻结的旧图片不挤占当前作答的 20 个名额。上传本身没有题目定位字段，不能把上传记录数当作单题图片数。
- 上传先通过 BlobStore 写入不可变文件，再事务内幂等登记 practice_user_resource；同用户同内容只一条，跨用户仍各自有归属。失败的孤立 blob 留待后续 GC。
- `X-QuizForge-Transient-Resource` 只接受缺省或 true。旧版 true 表示 history:false 的页面内临时资源；V2 强制保存历史，因此接受兼容标记但仍按用户持久化，避免刷新或历史丢图，也不因标记绕过上传限制。
- GET 只允许本人上传的资源，或本人可用题库引用的资源。每次重新鉴权，返回 private/no-store、nosniff 和内部文件路径；猜中其他用户的哈希也返回 404。
- 草稿和提交中的图片引用也验证访问权限，避免把他人 hash 植入自己的作答后被后续 AI / 历史通路读取。

## 提交截图与冻结

沿用答题区组件已有的截图文档形状：`answer` 内答题区的 `snapshot:{type:"image",attrs:{assetId}}`。现有基础题型页面 submit 只会 flush 后发送 answer，组件支持 snapshot 的加载但没有生成提交截图的入口。因此在宿主 bridge 的提交适配中补上：等待 flush 完成 → 以答题区模型绘制文字、插图和手写 → 上传截图 → 给这次提交的 answer 副本加入 snapshot → 发送 QF.save。不能异步上传后追改已经提交的答案；截图失败保留输入并中止提交。

服务端从本次 answer 提取并验证截图引用，不使用“用户最近上传”的隐式关联。提交后投影与单题冻结 payload 保留同一次 answer 的截图引用；重做、人工评分、回执重放不能把它替换为其他次作答的截图。图片未上传成功时不发送提交。快照不得加入原始 content_question.data。

RoundSnapshots 在提交事务中读取当前白板两层，不冻结判分前 PreparedDraft 中可能过时的白板副本；人工评分替换投影时保留提交图片关联。以上仅扩展快照内容，不改 SubmitService / DraftService / IdempotentDraftService 的锁顺序、事务范围和回执语义。

## GC 引用清单与后续边界

报告列出以下引用来源及 JSON 位置：作答状态 answer / answer_state、提交 projection / result、白板两层、practice_round_question.payload、轮次汇总可能的嵌套引用、未来 practice_history_annotation；以及已有 content_bank_asset、拓展包与文件。任何引用存在时不能清理；本次不删除资源、不实现 GC，也不新增历史接口。

作者已确认：本 PR 同步取消 L1.8 草稿、作答状态、轮次历史的计费；通过新迁移释放既有非 import 练习账目，保留私人题库 import 账目。练习写入不再调用记账通路。

## 验证与交付

- 单元：图片文件头与伪装、base64、大小边界；笔记追加与完成锁定；用户上传限流。
- MySQL / HTTP：资源去重和 IDOR、白板首次 / 连续保存、冲突、同 ID 并发与重放、失败回滚、两层读取、features 开关、提交态仍可写、已完成草稿不可写、截图关联与冻结。
- 性能回归：spy 证明白板保存不调用整库 questions、投影、判分及配额通路；在 300 题题库测一次保存并报告实际 SQL 与耗时。
- 独立本地 Compose + 浏览器：答题区打字、插图、手写、提交截图展示、刷新后两层仍在；不操作已有 localhost:8088 用户环境。截图存 git 忽略目录。
- mvn verify、前端测试及 CI 四个 job 全绿后 PR；报告文件清单、限制值、GC 引用清单、截图与未处理问题。测试环境 down，不删卷；完成后该工作目录 detach。
