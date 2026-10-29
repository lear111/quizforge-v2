# 练习空间接口映射

> 版本：v0.1；日期：2026-10-29；来源：quizforge_web@4522bb81c2126a699fcf72ce61d074dbc1264161 的 core/web。
> 后端盘点基线：main@f22e247。以当前 Controller 为准，不把尚未合并的 PR 算作已实现。

本次是 ADR-006 方案 C 的源码分叉。切片顺序：**1 导入与题库列表；2 打开题库；3 读题与渲染；4 保存草稿；5 提交；其余功能后续接入。** 一个页面可能跨多个切片；接口出现并不代表对应业务已经可用。

记号：`C=/api/collections/{kind}/{id}`，`Q=C/questions/{qid}`；各段均经过 encodeURIComponent。在线题库 kind 为 bank，考试为 exam。保留请求体 requestId/revision/contentVersion、题目 stamp 与 X-QuizForge-Question-Stamp，沿用 F-01。

## 页面、操作和接口

| 页面 / 操作 | 方法与接口 | 当前 V2 | 切片 | 前端调用位置 / 说明 |
| --- | --- | --- | --- | --- |
| 登录页注册 / 登录 | POST /api/auth/register、/api/auth/login | 已实现 | 1（前置认证） | public/auth.js；同源 HttpOnly Cookie |
| 进入练习空间 / 刷新 | GET /api/me | 已实现 | 1（前置认证） | app.js:start；先确认会话，再加载目录 |
| 账号页 / 退出 | GET /api/me；POST /api/auth/logout | 已实现 | 1（前置认证） | public/auth.js、app.js；退出后回到 / |
| 我的题库 / 刷新目录 | GET /api/library/index；缺失时 GET /api/catalog | 未实现 | 1 | shared/online.js、library-index-client.js；两者都缺失时显示明确空状态，不造题库数据 |
| 导入题库包 | POST /api/library/import，multipart 字段 file | 未实现（PR #43 待合并） | 1 | library-transfer.js；128 MiB、成功后重新读目录；响应 collections 用于导入数量提示 |
| 选择题库 / 从历史返回 | GET C | 未实现 | 2 | app.js:openCollection / returnToPractice / activateReady；首次打开还会继续请求首题和拓展页面 |
| 首题 / 切题 / 邻题预取 / 冲突后重读 | GET Q | 未实现 | 3 | app.js:loadQuestion / prefetchAdjacent；命中缓存时带 stamp 条件头 |
| 题目拓展页面 | GET /api/extensions/{pageId 或 id}/{version}/page | 未实现 | 3 | bridge/extension-pages.js；摘要如何填充 pageId 由后端响应契约确定，前端不猜测绑定 |
| 富文本静态依赖 | GET /api/sdk/{id}/{version} | 未实现 | 3 | app.js:prepareAssets、bridge/page-assets.js；固定组件的源码与构建产物位于 /shared/ |
| 富文本答题编辑依赖 | GET /api/sdk/{id}/{version}/editor | 未实现 | 4 | app.js:pageRequest；这是**答题富文本编辑**，不等于已删除的题目作者编辑器 |
| 显示图片 / Bridge resource-get | GET /api/resources/{sha256} | 未实现 | 3 | shared/api.js:readResource；经统一 Cookie request，再把图片交给 iframe |
| 听力材料 | HEAD / GET C/listening/{materialId} | 未实现 | 3 | listening-player.js；HEAD 经 request 显示认证/缺失提示，audio.src 同源直连 GET，保留 Range 流式读取 |
| 答案草稿 / 白板墨迹 | POST Q/actions，action=draft / whiteboard | 未实现 | 4 | app.js:mutate、shared/write-queue.js；原请求回执 ID、revision、失败输入保留与重试行为 |
| 上传答题资源 / 截图 | POST /api/resources | 未实现 | 4 | app.js:pageRequest；无历史场景仍带 X-QuizForge-Transient-Resource |
| 提交 / 人工评分 / 重做本题 | POST Q/actions，action=submit / review / retry | 未实现 | 5（submit），其余 | app.js / Bridge；不改写动作负载 |
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

## 当前验证边界

Compose 真后端验证账号、会话刷新、目录缺失、导入缺失与退出；Playwright 另以**显式模拟的目录响应**验证“切片 1 返回列表、切片 2 返回 404”时保留列表与友好提示。此模拟不代表题库列表已经在 V2 实现。上游 Bridge/白板/历史/考试保留源码，作答、保存、提交的端到端验收须等相应后端切片完成。
