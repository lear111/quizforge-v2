# M3 切片 2、3 验证报告

日期：2026-10-30。分支：feat/m3-read-slices；基于 main，随后 rebase 到仅新增 M3 笔记的 039a8a3。

## 改动与范围

- practice/application、infrastructure、web：实例归属与软删除检查；并发找到/创建普通练习 context；LEFT JOIN 题目状态与有序目录；读取我的作答、revision、白板及投影；条件版本戳短响应。
- practice/projection：按补充要求提供 ProjectionKey、ProjectionCache.get(key, loader)、NoopProjectionCache，以及 ConditionalOnMissingBean 的默认注册。未提交投影只从此接口获取；本次不实现缓存与并发合并。已提交仅返回数据库 projection。
- content/application、infrastructure、web：按确切审核摘要读取拓展页面；解析固定富文本服务注册表；SDK、图片与听力均检查当前用户的可用题库引用。Nginx 的 internal 路径发送 SDK/资源字节，blobs 只读挂载。
- 目录接口 `/api/catalog` 与 `/api/library/index` 补充只读实现，使已合并的导入流程能接入真实界面。没有增加管理、导出等操作。
- frontend：集中加载答题区组件；遵守后端只读能力；审核中的实例保留在列表且不自动打开；源码、组件和 Bridge 的其余行为保留。构建生成 SDK JSON，不提交产物。
- samples：来自 quizforge_web@4522bb8 的完整五题型页面/规则，转换成格式 4 的整组与单题库验收包。生成命令与摘要见 samples/README.md。
- 部署/构建：后端 Maven 只打包前端注册表元数据；Docker 从仓库根目录构建后端。CI 使用独立 Compose project，并运行新增浏览器验收。

未修改表结构、AuthService 或 DraftService。

## 测试

| 验证 | 结果 |
| --- | --- |
| JDK 21 `mvn -B -ntp verify` | 2784 项，0 失败，0 错误，3 跳过（原有 ArchiveReader 复现）；本次新增 29 项全部通过 |
| `npm run build`、`npm test` | 构建成功，26 项通过 |
| 隔离 Compose 的完整 Playwright | 桌面/手机各 3 项，共 6 项通过 |
| 补充真实资源 Range/HEAD/internal 检查后重跑读切片 | 桌面/手机各 1 项，共 2 项通过 |

`PracticeReadIntegrationTest`（25 项，Testcontainers MySQL + 真实 rule-runner）：

- overviewCreatesOneContextAndLeftJoinsUnansweredRowsWithoutRawAnswers：目录与默认状态、不查询原始答案。
- fiveRealTypesImportOpenAndProjectThroughTheOnlyCacheBoundary：五题型完整真实投影与页面读取；响应递归不含 correctOptionId / correctOptionIds / correctAnswer / answerAndExplanation。
- noopProjectsOnEveryUnconditionalReadButMatchingStampSkipsRunnerAndCache：正确的内容哈希/拓展摘要键、Noop 每次调用、短响应跳过投影；不暴露答案相关原始哈希。
- invalidStampIs400：4 类非法版本戳。
- draftReturnsOnlyMyAnswerRevisionAndWhiteboardAndCallsProjectOutsideTransaction：我的作答、revision、白板；runner 调用时没有读事务。
- submittedReturnsStoredProjectionWithoutCallingRunnerOrCache：存储投影直读。
- leftJoinKeepsQuestionsInPositionOrderAndNeverAttachesAnotherUsersState：有序目录、小题目录、他人状态排除、题号大小写精确匹配。
- concurrentOpensCreateOnlyOneNormalPracticeContext：在两条真实连接 INSERT 前 barrier 会合；uk_context_scope 去重。
- otherUsersDeletedCollectionsAndMissingQuestionsAre404、anonymousReadsRequireAuthentication：用户隔离、软删除与 401。
- reviewChangesBlockOverviewQuestionConditionalReadAndAllResourcesImmediately：pending/revoked/rejected/无审核记录均不能访问；旧 stamp 不能绕过。
- pageSdkAndImageRequireLiveReferencesAndExactDigestVersion：页面、SDK、编辑组件和图片的引用权限。
- listeningMetadataHidesPathsAndHeadUsesTheSameOwnershipChecks：听力元数据脱敏与大写扩展名音频 HEAD。
- sameIdAndVersionWithDifferentDigestServeTheExactReferencedPage：同 ID/版本不同摘要不混用页面。
- ruleRejectionNeverFallsBackToRawQuestion：3 类规则错误；missingProjectedFieldIs503WithoutRawDataFallback 与 stoppedRunnerIs503WithoutRawDataFallback：投影缺失、真实停止 runner 均不返回原始数据。

`ProjectionConfigurationTest`（4 项）：Noop 每次调用 loader、异常原样传播、默认 bean、替换实现让默认 bean 退让、键区分内容/拓展。

## 真实浏览器与截图

使用独立 `qf-m3-read-slices` project，http://localhost:18097，Chrome 自动化完成注册 → 上传 samples/basic-types.qbank → 显示审核中 → 只对本次样例摘要设置测试白名单 → 打开/读题/渲染 → 刷新。测试白名单通过独立数据库夹具设置，没有新增或绕过生产审核逻辑。

桌面 1440×960、手机 375×812；断言无页面 JS 错误、无横向滚动、题卡不是错误页、富文本图片实际解码。SDK 返回真实脚本；Nginx 资源 Range 返回 206 和正确 PNG 头，HEAD 返回正确长度；internal SDK/blob URL 与公开 richtext bundle URL 不能绕过鉴权访问。

| 题型 | 桌面 | 手机 |
| --- | --- | --- |
| 单选 | [截图](read-desktop-single-choice.png) | [截图](read-mobile-single-choice.png) |
| 多选 | [截图](read-desktop-multiple-choice.png) | [截图](read-mobile-multiple-choice.png) |
| 判断 | [截图](read-desktop-true-false.png) | [截图](read-mobile-true-false.png) |
| 简答 | [截图](read-desktop-short-answer.png) | [截图](read-mobile-short-answer.png) |
| 答题区简答 | [截图](read-desktop-answer-area.png) | [截图](read-mobile-answer-area.png) |

已执行 `docker compose -p qf-m3-read-slices -f deploy/docker-compose.yml down -v`，确认容器、卷、网络无残留；Testcontainers 已回收。本人的工作目录在推送后切换为 detached HEAD，不占用此分支。

## 尚未处理

- 真正的投影缓存及其 single-flight、容量与淘汰策略，由后续实现替换 Noop。
- 审核流程、actions HTTP 接口、提交、白板写入、汇总、历史、考试、资源上传、完整练习频率限制仍属后续切片；当前页面显示写入尚未开放。
- 浏览器初次验收曾把答题区隐藏的提交截图占位图当成题库图片断言；已改为检查实际资源图片，未为此修改题型页面。未发现需要额外修改生产业务逻辑的遗留缺陷。
