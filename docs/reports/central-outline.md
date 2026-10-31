# 集中大纲（格式 4 v0.7）实现报告

## 行为与接口

- 顶层 `outline` 必填；校验分组、题目引用、互斥 label/parts、纯文本、数量/长度、parts 能力与父题内 ID 唯一性。每题旧 `outline` 拒绝；错误沿用 `INVALID_QUESTION_BANK` / 示例的 `INVALID_EXTENSION`，附题库及 `outline[g].items[i]` 等定位。
- 所有作者题目仍执行结构、绑定、资源引用、Schema、validateBank、适用的 outlineBatch 和 scoreBatch 校验。全部成功后仅将大纲引用题按出现顺序写入 content_question，position 从 0 连续递增；question_count 与数据库满分汇总只包含这些题。
- label/parts 映射到现有 `{level:"question",label}` / `{level:"parts",items}`，省略时仍派生或 NULL。题目哈希算法不变，只收最终大纲，不收分组、分组序号、题序。
- V5 增加 `outline_group_index INT NULL`（outline 数组下标，从 0 开始）、`outline_group_name VARCHAR(80) NULL`。旧行保留原内容，新增列为 NULL；不推测作者分组。
- 概览 `questions[]` 和读题 `question` 新增 `outlineGroupIndex`、`outlineGroupName`；前端按相邻行的组序号分组，同名相邻组仍分别显示，未命名组不生成标题；不再按拓展题型分组。状态及导航逻辑保持原样。
- 导入返回 `collections[].warnings`，无遗漏时为 `[]`，否则为 `[{code:"QUESTIONS_NOT_IN_OUTLINE",message:"N 道题未出现在大纲中，不会显示",count:N,questionIds:[...]}]`；ID 顺序与 questions 数组一致。网页导入完成提示显示数量与 ID。
- 未引用题依旧进入完整 bank.json 内容身份；按用户确认，摘要清单也保留 questions 数组的全部绑定，已在设计 06 §9.1 补充说明。

## 文件清单

- 生产 Java：`content/domain/BankStructureRules.java`、`content/application/ImportService.java`、`content/infrastructure/QuestionRow.java` / `QuestionMapper.java`、`practice/infrastructure/PracticeReadRows.java` / `PracticeReadMapper.java`、`practice/application/PracticeViews.java` / `PracticeReadService.java`。
- 迁移：`backend/src/main/resources/db/migration/V5__central_outline_groups.sql`。
- 后端测试：`CentralOutlineTest.java`、`CentralOutlineMigrationTest.java`（新增）；`ImportIntegrationTest.java`、`PackageFixture.java`、`PackageInheritedRulesTest.java`、`QbankPackageParserTest.java`、`ContentMapperTest.java`、`ImportPrepMapperTest.java`（升级夹具并补充断言）。
- 前端：`frontend/practice/app.js`、`outline/outline-groups.js`、`outline/outline-view.js`、`frontend/UPSTREAM.md`；新增 `frontend/test/outline.test.mjs`、`frontend/test/browser/central-outline.spec.mjs`。
- 样例：`samples/build.py`、`samples/README.md`、`basic-types.qbank`、`single-choice.qbank`、新增 `central-outline.qbank`。
- 文档：`docs/design/06-qbank-package.md` §9.1 与本报告。

## 测试对应

| 规则/通路 | 测试 |
| --- | --- |
| outline 必填、禁止旧逐题 outline | CentralOutlineTest.missingRootOutlineAndLegacyPerQuestionOutlineAreRejected |
| 分组/引用/类型/白名单/互斥/纯文本/长度/数量、未知/重复/大小写引用与位置 | rejectsEveryInvalidGroupAndReferenceWithLocation（参数化） |
| parts 对象/长度/数量/纯文本/ID 唯一 | partsEnforceShapePlainTextUniqueIdsAndBounds（参数化） |
| parts 能力、100 项及 128/80 长度边界 | partsRequireDeclaredCapabilityAndAcceptExactLimits |
| 10000 组与10000题目项正例 | groupsAndItemsAcceptTenThousandAndCaseSensitiveStableIds |
| 可选 group/label/parts、保留作者值 | omittedLabelsPartsAndGroupsAreValidAndAuthorValuesRemainUnchanged |
| 父题之间可重复小题 ID、ID 大小写敏感 | partIdsAreScopedToTheirQuestionAndCaseSensitive |
| 未引用题字段/绑定仍校验 | unreferencedQuestionStillGetsAllStructureChecks |
| 拓展示例也须集中大纲 | extensionExamplesAlsoRequireCentralOutline |
| 实际 SQL 顺序、组序号/名、label/parts、警告、数量/满分、读题404及概览排除、答案不泄漏 | ImportIntegrationTest.centralOutlineControlsPositionsGroupsWarningsReadVisibilityAndTotalScore |
| 改组名/组序号/题序不改题目哈希，改未引用作者内容仍改题库哈希 | groupingAndOrderingChangeBankHashButNotQuestionHashAndUnusedContentStillChangesBankHash |
| 仅未引用题的拓展内容变化：bank.json 字节相同，题库哈希不同，可见题哈希相同 | changingOnlyUnreferencedQuestionsExtensionChangesBankIdentity |
| 未引用题 Schema/规则/分数精度/派生大纲错误均拒绝且无业务残留 | invalidUnreferencedQuestionStillRejectsEntireImport（4 项，真实 rule-runner） |
| 原派生与显式大纲规则 | mixedPerQuestionBindingsKeepOrderScoresAndExplicitOutline / generatedOutlineUsesPartsShapeAndNeverOverridesExplicitOutline（升级输入格式） |
| MyBatis 新增列插入/批量/读取及 NULL | ContentMapperTest / ImportPrepMapperTest 原用例增加字段 |
| V4 原行升级 V5 后内容、顺序、哈希不变 | CentralOutlineMigrationTest.v4QuestionsSurviveV5WithNoInventedGroupOrChangedContent |
| 前端同名组不合并、不同题型不拆组、无名组、连续编号、旧行与空列表 | frontend/test/outline.test.mjs（6 项） |
| 真实浏览器同名组两个标题、未命名组、label/parts/自动编号、隐藏题不可见、小题高亮、375px | frontend/test/browser/central-outline.spec.mjs（桌面/手机） |

旧逐题大纲的 13 项用例由集中大纲边界用例替换，不再测试已移除的合法输入形状。所有既有测试夹具在生成处升级；rule-runner 的固定旧版参照文件与摘要向量不修改。

## 样例

`basic-types.qbank` / `single-choice.qbank` 的所有题库与拓展示例包含顶层大纲，每本实际题库另含一个未引用题。`central-outline.qbank` 为简答题单题库包：questions 数组与大纲顺序不同，两个相邻同名作者组、一个无名组，分别使用 label、真实 parts ID、省略两者；另含未引用题。生成命令及 SHA-256 见 samples/README.md。拓展规则、页面、manifest 保持固定上游字节；示例 JSON 格式转换会改变审核摘要，重新导入需要审核。

## 文档澄清与边界

1. 原任务“相邻且分组相同”与 §4.3“同名不合并”冲突：用户已明确按数组下标区分，遵循后者。
2. §9.1“按题目顺序”的歧义：用户已明确保留 questions 全数组顺序及未引用题摘要，已补文档和真实导入回归测试。
3. 附录 A.2 的题库根白名单/必填列表仍未列 outline，questions 行仍写“保持题序”；实现按 v0.7 正文 §4.3，未修改这两处旧附录文字。
4. §4.3 整体规则 6 的“更新时 ID 不应改变”是跨版本作者契约，本次独立导入无法识别作者是否将某题改名；只校验本包精确引用、父题内唯一与保留原 ID，不增加跨版本推断。
5. 尚未实现的整卷考试/得分汇总接口不在本任务添加；题目表已排除未引用题，测试验证数据库题数和满分总和。

未修改 QuestionStateMapper、DraftService、IdempotentDraftService，也未修改已有迁移。

## 验证

最终 Maven、前端、浏览器及 CI 结果完成后填写在 PR 描述。
