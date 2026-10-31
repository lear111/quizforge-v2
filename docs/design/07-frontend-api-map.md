# 练习空间接口映射

> 版本：v0.3；日期：2026-10-30；来源：quizforge_web@4522bb81c2126a699fcf72ce61d074dbc1264161 的 core/web。
> 后端盘点基线：main@40a6a52，加本次切片 5 的提交 HTTP 接线。以当前 Controller 为准。

本次是 ADR-006 方案 C 的源码分叉。切片顺序：**1 导入与题库列表；2 打开题库；3 读题与渲染；4 保存草稿；5 提交；其余功能后续接入。** 一个页面可能跨多个切片；接口出现并不代表对应业务已经可用。

记号：`C=/api/collections/{kind}/{id}`，`Q=C/questions/{qid}`；各段均经过 encodeURIComponent。在线题库 kind 为 bank，考试为 exam。保留请求体 requestId/revision/contentVersion、题目 stamp 与 X-QuizForge-Question-Stamp，沿用 F-01。

## 页面、操作和接口

| 页面 / 操作 | 方法与接口 | 当前 V2 | 切片 | 前端调用位置 / 说明 |
| --- | --- | --- | --- | --- |
| 登录页注册 / 登录 | POST /api/auth/register、/api/auth/login | 已实现 | 1（前置认证） | public/auth.js；同源 HttpOnly Cookie |
| 进入练习空间 / 刷新 | GET /api/me | 已实现 | 1（前置认证） | app.js:start；先确认会话，再加载目录 |
| 账号页 / 退出 | GET /api/me；POST /api/auth/logout | 已实现 | 1（前置认证） | public/auth.js、app.js；退出后回到 / |
| 我的题库 / 刷新目录 | GET /api/library/index；缺失时 GET /api/catalog | 已实现（本次只读入口） | 1 | shared/online.js、library-index-client.js；两者都缺失时显示明确空状态，不造题库数据 |
| 导入题库包 | POST /api/library/import，multipart 字段 file | 已实现（PR #43） | 1 | library-transfer.js；128 MiB、成功后重新读目录；响应 collections 用于导入数量提示 |
| 选择题库 / 从历史返回 | GET C | 已实现 | 2 | app.js:openCollection / returnToPractice / activateReady；首次打开还会继续请求首题和拓展页面 |
| 首题 / 切题 / 邻题预取 / 冲突后重读 | GET Q | 已实现 | 3 | app.js:loadQuestion / prefetchAdjacent；命中缓存时带 stamp 条件头 |
| 题目拓展页面 | GET /api/extensions/{pageId 或 id}/{version}/page | 已实现 | 3 | bridge/extension-pages.js；pageId 为所绑定拓展的审核摘要；同 ID/版本不同摘要分开路由与缓存 |
| 富文本静态依赖 | GET /api/sdk/{id}/{version} | 已实现 | 3 | app.js:prepareAssets、bridge/page-assets.js；固定组件的源码与构建产物位于 /shared/ |
| 富文本答题编辑依赖 | GET /api/sdk/{id}/{version}/editor | 已实现（仅组件读取） | 4 | app.js:pageRequest；这是**答题富文本编辑**，不等于已删除的题目作者编辑器 |
| 显示图片 / Bridge resource-get | GET /api/resources/{sha256} | 已实现 | 3 | shared/api.js:readResource；经统一 Cookie request，再把图片交给 iframe |
| 听力材料 | HEAD / GET C/listening/{materialId} | 已实现 | 3 | listening-player.js；HEAD 经 request 显示认证/缺失提示，audio.src 同源直连 GET，保留 Range 流式读取 |
| 答案草稿 | POST Q/actions，action=draft | 已接入 | 4 | app.js:mutate、shared/write-queue.js；requestId/revision/action/data.answer；保留失败输入与 409 恢复 |
| 白板墨迹 | POST Q/actions，action=whiteboard | 501 FEATURE_NOT_AVAILABLE | 后续 | capabilities.canWhiteboard=false；不把墨迹写入作答草稿 |
| 上传答题资源 / 截图 | POST /api/resources | 未实现 | 4 | app.js:pageRequest；无历史场景仍带 X-QuizForge-Transient-Resource |
| 提交 | POST Q/actions，action=submit | 已接入 | 5 | app.js / Bridge；requestId/revision/data.answer；返回提交后投影与状态，重放不重新判分 |
| 人工评分 / 重做本题 | POST Q/actions，action=review / retry | 501 FEATURE_NOT_AVAILABLE | 其余 | 保留拓展动作负载 |
| 本轮分值汇总 | GET C/summary | 未实现 | 5 | app.js:showScoreSummary、practice-summary.js |
| 完成本轮 / 再练一轮 | POST C/finish、C/restart | 未实现 | 其余 | app.js:finishPractice / restartPractice；保留 roundId、summaryVersion、requestId |
| 历史列表 / 冻结快照 | GET C/history、C/history/{historyId} | 未实现 | 其余 | app.js、history/history-view.js |
| 删除历史 | DELETE C/history/{historyId} | 未实现 | 其余 | app.js:renderHistoryList；未实现错误不伪装成删除成功 |
| 题库实例管理 / 分组 | POST /api/library/manage | 未实现 | 其余 | library-management.js；仅保留管理入口，不新增服务端逻辑 |
| 导出题库 / 分组 | POST /api/library/export | 未实现 | 其余 | library-transfer.js；返回 Blob，失败仍显示友好提示 |
| 考试列表 / 开始 | GET / POST C/exams | 未实现 | 其余 | exam/exam-mode.js |
| 考试状态 / 交卷 / 删除 | GET / POST / DELETE /api/collections/exam/{id}/exam | 未实现 | 其余 | exam-mode.js；状态轮询沿用上游 |
| 考试题卡页面 | GET /api/collections/exam/{id}/questions/{qid}/page | 未实现 | 其余 | app.js:showQuestion / prefetchAdjacent |
| 考试资源 | GET / POST /api/collections/exam/{id}/resources[/{sha256}] | 未实现 | 其余 | app.js:pageRequest |
| AI 评分 / 当前任务 | POST Q/ai/grade；GET Q/ai/current[?targetId=…] | 未实现 | 其余 | ai/ai-client.js；保留平台评分协议，删除本机模型设置 |
| AI 任务 / 重试 / 确认 | GET Q/ai/tasks/{taskId}；POST …/retry、…/confirm | 未实现 | 其余 | ai-client.js；确认保留 requestId / candidateVersion |

F-01 中另有 `GET Q/stamp`；这份上游快照当前通过 GET Q 的条件头校验 stamp，**没有单独调用 /stamp**，故不把它列为本分叉的实际请求。健康检查 /api/health 已实现，供部署/CI 使用，练习页面不调用。

## 降级与恢复原则

- 所有宿主 API 请求经过 practice/shared/api.js；401 导向 `/?returnTo=…`，登录后仅允许跳回同源 /practice/ 下的路径，保留 query/hash。
- 404 / 501 转为 FEATURE_NOT_AVAILABLE。404 也可能意味着实体已删除，因此提示为“该功能尚未开放，或内容已不存在”，不声称所有 404 都是服务端缺接口。
- 只有目录启动使用受控空状态：先尝试 index，再尝试 catalog；两者缺失才标记 unavailable。503、网络错误、409 不伪装成空列表或成功写入；保留原错误和输入恢复路径。
- 将来任一目录接口实现后，正常数据自动进入上游列表组件；用户能点击题库，后续切片缺失仍停留在原界面并显示提示。不会用前端模拟数据作为“后端已接通”的证据。
- 删除网络设置、开发者/本机 AI 设置、本机访问密码与 Hub 设备登录、题目作者编辑器、拓展示例入口；启动不再调用这些配置 API。外观保留在独立本地对话框，仅存设备偏好，不存认证状态。

## 切片 2、3 的响应与边界

- 目录 `/api/catalog`、`/api/library/index` 返回当前用户未软删除的实例；`banks[].availability` 包含 available/code/message。未审核或已撤销的题库显示“审核中”，不会自动打开；点击后仍由后端实时复核，返回 422 CLOUD_EXTENSION_REVIEW_REQUIRED。
- 概览返回 id/kind/title/revision/description/features/listening/questions/states/availability/readOnly。questions 按 position 排序，包含 id/title/type、可选 outlineLabel/outlineItems/listening；state 用 user_id 限定的 LEFT JOIN 读取，缺失行默认为 unanswered/revision=0。概览不查询题目 data。
- 首次打开创建 kind=practice、scope_key=default 的 context。并发直接 INSERT，由 uk_context_scope 去重后读取胜方；不在未命中的 UPDATE 上升级间隙锁。
- 读题返回 question:{id,title,data:投影}、state:{status,answer,result,revision,answerState,outlineStates}、draft、stamp、extension、features、capabilities、aiTask:null。已提交仅取持久化 projection；未提交通过 practice/projection 的 `ProjectionCache.get(ProjectionKey,Supplier<String>)`。当前由 CaffeineProjectionCache 实现；NoopProjectionCache 保留为 ConditionalOnMissingBean 注册的回退实现。
- stamp 的 contentVersion/packageVersion 是进程私钥 HMAC 版本戳，不暴露含答案的原始内容摘要；重启后自动失配重读。合法匹配头返回 unchanged/stamp，跳过投影；缺少/非法字段返回 400 VALIDATION_FAILED。可用性先于短响应校验，撤销审核后不能通过旧 stamp 绕过。
- extension/type 包含 id/version/name/pageId；pageId 使用确切审核摘要。页面响应兼容旧 apiVersion/html/script/style/dependencies/contentApi/assets。答题区组件通过共同的 page-assets 适配加载受保护的 quizforge.answer-area/1.4.1 SDK；旧冻结页面自带组件时保持原样。
- SDK JSON 在 frontend 构建时由固定的组件源码生成，不提交构建产物；后端复核用户的可用题库引用，Nginx internal 路径发送。题库图片、听力同样先鉴权再 X-Accel-Redirect，blobs 在 Nginx 只读挂载；所有响应 private/no-store，以便下一次请求重新检查撤销状态。公开路径不能绕过 internal 限制。
- 题库听力材料只公开 id/title/revision，隐藏存储路径；GET/HEAD 发送音频，Range 由 Nginx 处理。
- 未提交题目 canSave=true、canSubmit=true；已提交题目两项均为 false；其余写能力仍为 false。概览含有未提交题目时 readOnly=false，全部已提交时为 true。前端提示草稿自动保存、可提交判分，白板尚未开放。生产投影缓存已由 Caffeine 提供（PR #54）；历史、汇总、人工评分、重做、考试、资源上传、审核流程与完整练习限流仍待后续切片。

## 切片 4：草稿 HTTP 与回执

- POST Q/actions 接收 `{requestId,revision,action:"draft",data:{answer},contentVersion?}`；requestId 非空且最多 64，revision 为可保存的非负整数。answer 可为 JSON null（清空），但不能省略 data.answer。前端额外携带的 answerState 当前没有持久化通路；本切片只保存 answer，不伪造已保存的 answerState。
- userId 来自会话；实例归属、题目精确 ID、contextId、内容哈希、审核可用性由服务端解析。客户端 contextId/userId/contentSha256 不参与定位或入库。可选 contentVersion 校验使用服务端不透明版本戳，不作为客户端提供的内容哈希。
- IdempotentDraftService 在同一事务中保存草稿和 `{revision:N}` 回执。HTTP 适配按回执的 revision 与通过幂等检查的请求答案构造完整读题响应（另带顶层 revision），满足 onSaved 对 question/state/stamp/extension/features/capabilities 的需要；不重新读取可能已前进到 N+1 的作答状态。投影在事务前加载，仍然只使用未提交投影缓存；提交态答案不会进入草稿响应。
- 不同 requestId 的旧 revision 返回 409 REVISION_CONFLICT。前端保留 iframe 内输入，并重读最新版本，显示重试/读取已保存内容的恢复入口。相同 ID 不同答案返回 400 REQUEST_ID_REUSED；作者在 ac90283 修复状态码，契约测试已启用，见 [本切片报告](../reports/m3-draft-actions.md)。
- 白板、人工评分、重做等操作返回明确的 501 FEATURE_NOT_AVAILABLE，不产生回执或草稿。请求和响应仍经同源 Cookie、Origin 检查和 no-store 传输。

## 当前验证边界

MySQL + 真实 rule-runner 集成测试覆盖五种题型导入/读题、并发 context 去重、归属与软删除、投影防答案泄露、草稿/提交状态、版本戳、审核撤销、页面/SDK/图片权限与故障响应。Playwright 在独立 Compose 环境用 samples/basic-types.qbank 走注册 → 导入 → 审核中 → 测试白名单 → 打开五本题库 → 实际 iframe 渲染（含富文本、图片、答题区），桌面及 375px 宽度均截图。截图与报告位于 docs/reports/m3-read-slices/；测试夹具不代表审核业务已实现。原有“目录有数据、缺失接口友好降级”的显式模拟测试继续保留。

## 切片 5：提交判分与轮次快照

- `action=submit` 复用写队列的 `requestId/revision/data.answer/contentVersion`；身份只取会话，题目指纹和上下文由服务端解析。作者的 SubmitService 先保存草稿、在事务外判分，再在一个事务中检查 revision、提交状态、轮次快照和回执。
- 回执保持 `{revision:N}`；HTTP 返回完整 question/state/stamp 等字段。重放从已提交状态和通过幂等检查的请求答案构造响应，使用持久化投影，不调用判分或写入投影缓存。
- `RoundSnapshots.collection` 冻结标题、题序、大纲及分组、题目指纹；`question` 冻结 GradeOutcome 的提交后投影、作答、结果与状态，不保存原始题目 data。
- 判分语义由拓展决定：普通简答题返回 graded/0，后续由 review 改分；答题区简答题返回 pending/null。宿主保留 gradingStatus 与空分数；数值列按提交服务既有 HALF_UP 四位小数规则存储，HTTP 和快照使用相同数值。
