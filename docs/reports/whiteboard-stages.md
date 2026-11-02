# 白板按提交阶段自动切换

## 产品更正与实现

按作者 2026-11-01 确认的规则：未提交时编辑作答草稿；本题提交成功后草稿立即冻结，自动编辑追加笔迹；历史中同样只编辑追加笔迹。“追加”不代表只能添加，已保存的追加笔迹允许编辑、擦除、撤销。移除层选择框，只显示当前阶段。

继续使用既有 draft / notes 存储，不迁移、删除或重写用户已有笔迹。整轮完成后未作答题的草稿也冻结；原有重做本题的重置语义不变。未修改 SubmitService、DraftService、IdempotentDraftService 或 QuestionStateMapper。

- WhiteboardService 仍在同一事务中按原顺序锁 context、白板，查回执、校验独立 revision；只查询本题是否 submitted，禁止提交后改 draft、提交前写 notes。追加笔迹不能复用冻结草稿的 stroke ID，避免客户端渲染覆盖。
- PracticeReadService / RoundSnapshots 把提交状态传给 WhiteboardViews，读题与快照返回正确 draftLocked。
- PracticeHistoryService 允许修改和擦除 notes，仍检查权限、revision、requestId、大小限制；更新独立 annotation，不修改冻结快照的草稿、作答和评分。
- 前端按提交/历史状态自动选择阶段，仅保护另一层的笔迹；提交前先 flush 白板，提交请求期间暂停画笔，成功后切到追加阶段，失败后恢复。
- 仍限制两层合计 2 MiB；不接入配额；只查询当前题状态，不读题目原文或整本题库，不调用 rule-runner。写入仍只有白板与幂等回执行；历史写入为 annotation 与回执。

## 验证

- WhiteboardDocumentsTest：阶段权限、完成后未作答题、追加笔迹允许编辑/擦除、冻结 ID 不得覆盖、格式校验。
- WhiteboardResourceIntegrationTest：提交后拒绝清空/替换草稿；追加笔迹编辑、擦除、幂等重放；等候提交事务的旧草稿写入被拒绝；完成后未作答题只允许 notes；既有 IDOR、大小、独立 revision、并发、回滚、300 题热路径检查。
- PracticeCompletionHistoryIntegrationTest：历史 notes 编辑、擦除、幂等与 409，冻结作答/草稿不变；完成与归档其余回归。
- 前端单元测试：自动选择阶段、只提交活动笔迹、冻结笔迹在移动/擦除/撤销/清空后保持不变、答案墨迹隔离。
- Playwright：桌面与手机均验证无选择框、提交自动切换、刷新仍处于追加阶段、橡皮擦掉追加笔迹而原草稿不变；历史页面显示追加阶段。

执行结果、CI 与截图见 PR 描述。只使用本机独立 Compose 与合成测试题库；测试结束 down，工作目录 detach。实际用户当前正在运行的服务由作者审查合并后更新，本任务不重启它。
