# 上线前安全审查（2026-11）

执行者：Codex。基础提交：`dbd0e21`（已含 L6、#120、#122）。审查包含 33 个 HTTP Controller、安全过滤链、归属查询、后台任务、浏览器宿主、拓展沙箱、Nginx 与 Compose。只使用本机合成数据、Testcontainers 和独立 CI Compose；没有连接业务服务器、读取 SSH 配置或本机密钥文件。

本文的“已确认安全”限于下述代码路径、威胁模型与测试，不表示对所有未来拓展、依赖或运行环境的安全保证。没有进行真实模型调用、正式环境渗透测试或商业依赖漏洞数据库扫描。

## 1. 十项检查结论

| 检查项 | 结论 | 检查路径与证据 |
| --- | --- | --- |
| 1. IDOR | 发现问题并修复；其余已检查路径未发现越权 | 练习、考试、历史、白板、用户图片、私人包、通知、AI 任务/流水、设备、评论与举报的用户身份来自 Principal 或已校验设备令牌。`PracticeReadMapper.collection`、`ExamMapper.OWNED`、`PracticeHistoryMapper.find/entries`、`MyLibraryMapper.original`、`NotificationMapper.findVisible`、`AiGradingMapper.find/lock`、`DeviceMapper.owned/revoke` 等带用户条件。截图读取原先在 Java 分段判断，改为 `CatalogMapper.visibleFile` 一条 SQL 限定归属、管理员或当前已审核公开版本，见 SR-05。 |
| 2. 管理接口 | 已确认安全（已检查范围） | `SecurityConfig` 的 `/api/admin/**` 统一调用 `AccountAdminService.isAdmin`，实时排除被封禁账号；用户提供的 ID 不决定管理员身份。`AdminConsoleIntegrationTest.everyAdminEndpointRejectsAnonymousAndNonAdminUsers` 扫描管理路由并验证匿名/普通用户拒绝。维护命令只由 `MaintenanceCommand` 的非 Web 入口启动，不注册 HTTP 路由。 |
| 3. 匿名接口 | 已确认安全（已检查范围） | 健康、公开配置、注册/登录/找回密码；已审核公开广场的列表、详情、评论、截图、原文件；L6 能力、固定版本导入清单/下载与连接授权入口。`PlazaMapper.VISIBLE` 要求 public、active、当前 approved_revision、审核通过且校验通过；待审核新版本不参与公开查询。连接页只是授权界面，批准必须有会话；设备接口必须有 Bearer，Cookie 不能代替。 |
| 4. 认证与会话 | 发现问题并修复 | Cookie 为 `__Host-qf_session; HttpOnly; Secure; SameSite=Lax; Path=/`，无 Domain；会话令牌只存哈希，每次查询检查 idle/absolute、封禁。浏览器写请求严格匹配 Origin；仅 L6 的 PKCE/Bearer 接口豁免，不接受 Cookie 代替凭据。登出、封禁已有失效检查；找回密码漏撤销设备授权已修复（SR-01），并统一兑换授权码的锁顺序（SR-07）。找回密码已知/未知账号的发送响应一致；验证码限次、过期、一次性及登录错误统一提示由既有测试覆盖。 |
| 5. 上传与文件 | 已确认安全（已检查范围） | `QbankArchiveReader` 的白名单路径、重复名/大小写、非法编码、CRC、实际解压计数、条目/累计大小检查；`BlobStore` 校验哈希、拒绝符号链接、原子发布。上传图片按文件头限定 PNG/JPEG/WebP，4 MiB；截图受数量/大小限制。资源查询检查上传者或有权访问的当前/考试/历史资源。Nginx 的 blob/SDK alias 都为 internal，X-Accel 只能由数据库哈希或 SDK 白名单拼接，不接受用户文件路径；大文件不经 Java 传输。 |
| 6. 答案可见性 | 已确认安全（按 FR-11 的明确例外） | 未提交读题只返回 project，概览不带 data；缓存只用于未提交投影。浏览器判分原文在状态锁定为 submitted 后、仅本用户本题下发；AI prepare 也要求已提交。`BrowserGradingIntegrationTest.rawDataIsUnavailableBeforeLockAcrossQuestionsAndAcrossUsers`、`PracticeReadIntegrationTest`、`AiGradingIntegrationTest.requestIdReuseAndIdorDoNotExposePrivateMaterials` 覆盖边界。**原始 .qbank 下载本就含作者答案**，FR-11 v1.17 允许已审核公开包匿名下载、所有者下载自己的原文件；不能将此产品决定误报为提交前泄露漏洞。 |
| 7. 注入与 XSS | 已确认安全（已检查范围） | 全部 `*Mapper.java` 搜索无 `${}`，动态排序使用固定 choose 分支，值均以 `#{}` 绑定。Hub 评论/通知/昵称/题库信息使用 textContent，评论保留换行但不解析 HTML；宿主没有将这些字段交给 Markdown/HTML 渲染器。练习宿主 innerHTML 的已检查用途为固定图标/骨架；拓展 HTML/富文本在不透明来源沙箱中。停用历史仅用 textContent 显示固定提示，不呈现冻结题目内容或图片（新增前端回归）。 |
| 8. 资源滥用 | 关键昂贵路径已有约束；统一频控策略需作者决定 | 上传 128 MiB、每日 20 次（北京日界线）、私人原文件配额在事务内预留；并发队列槽位和后台名额不能由用户参数扩大。图片每用户 30 次/分钟、4 MiB，单题引用 20 张；白板 2 MiB。浏览器判分单批 20、投影/请求大小校验；AI 输入/图片/输出 token、余额检查、每用户与站点并发、3 次模型调用上限及检查点/扣费 ref 幂等。L6 模型、ref 均由宿主决定，不能传任意 provider URL 或借 ref 重用逃费。**并非每一个廉价写接口都有单独用户限频**，见第 5 节，不将“有 Nginx 总大小限制”夸大为全面限频。 |
| 9. 密钥与配置 | 发现问题并修复；部署检查仍需完成 | 本地完整可达 Git 历史扫描未发现所列规则的密钥候选，范围与局限见第 4 节。provider 模式已有验证码 pepper（32 字符）与 AI AES-GCM 主密钥（32 字节）检查，fake 只允许本地/测试来源。补充拒绝正式配置继续使用默认 runner 令牌、默认数据库密码（SR-04）。模型客户端明确关闭请求/响应日志；日志记录 Key ID 而非 Key，验证码生产发送失败只记录掩码联系方式与固定错误。开发 fake 日志会记录**合成验证码**，不得在真实域名开启。 |
| 10. 拓展与沙箱 | 发现问题并修复；导入待审校验为设计中的受限例外 | 普通练习、考试、AI、浏览器规则/页面与 SDK 的入口按实时白名单判定。历史原有脚本绕过已修复（SR-02）；导入不再执行已 revoked/rejected 摘要（SR-03）。**未知/待审拓展必须先在导入专用服务器沙箱校验**，这是上传队列的既有设计，不是允许向浏览器下发。runner 主进程不执行拓展，子进程权限/96 MiB/超时/队列/输出受限；浏览器 opaque iframe 内 Worker，CSP 禁网络，650 ms 钩子限制，消息校验 source 与随机 session。 |

## 2. 发现的问题、复现与修复

### SR-01：找回密码后旧设备凭据仍可用（高）

复现：授权一个 L6 设备并留下另一枚尚未兑换的授权码 → 通过验证码找回密码 → Cookie 会话失效，但旧设备令牌仍可访问设备接口，旧授权码也仍可换取令牌。

修复：`AccountService.resetPassword` 在原有密码更新/验证码消费事务中撤销全部设备并删除该用户授权码；不影响其他用户。`DeviceService.exchange` 的普通读之后仍以删除影响行数保证一次性，重置先提交时兑换不能重新签发。

回归：`AccountCompletionIntegrationTest.passwordResetRequiresCaptchaRevokesSessionsAndHidesUnknownAccounts` 增加同用户设备/授权码清理与其他用户保留断言；L6 既有封禁、撤销、Cookie 不能代替设备令牌测试继续运行。

### SR-02：历史仍能执行已撤销拓展（高）

复现：形成历史 → 将题库更新到其他内容 → 撤销旧拓展摘要 → 原 `ContentAccessMapper.extension/manifests` 的历史分支不检查审核状态，仍能取得旧拓展脚本与 SDK。

作者已于 2026-11-05 调整决定：撤销优先于历史执行。修复历史 SQL 的实时批准条件；无法执行时只返回 unavailable 标记，不返回 html/script/style。该题位置仅显示“该题的历史记录已丢失（题型拓展已被管理员停用）”，不呈现通用视图、作答、分数、图片或白板入口，不创建 iframe，不加载 SDK。冻结投影、作答、评分、白板与图片均不删除，删除历史时照常释放；重新批准同一摘要后恢复原页面及白板。文件被管理员移除时也仅显示提示，保留原“资源已被管理员移除”文案。同步修改验收清单 §6。

回归：`PracticeCompletionHistoryIntegrationTest.historyAssetsRemainAuthorizedAfterBankChangesUntilHistoryIsDeleted` 覆盖拒绝直接取页面、无脚本、准确提示、冻结 payload 不变、图片权限保留、重新批准恢复及删除后图片权限释放；`removedExtensionPageKeepsHistoryAndRegistersReadablePlaceholder` 检查无替代脚本。`history-unavailable.test.mjs` 检查仅一个纯文本提示、无题目/作答/图片/iframe/script、隐藏白板及重新批准后恢复；桌面与手机的 `practice-lifecycle.spec.mjs` 实际撤销、仅显示丢失提示、再批准恢复页面与白板笔迹。

### SR-03：导入校验仍执行已撤销/拒绝拓展（中）

复现：先导入拓展使其登记 → 管理员撤销或拒绝 → 清空规则校验缓存并再次上传 → 旧队列先调用 capabilities/validateBank 等，再把审核结果判为不可用。

修复：`QueuedImportValidation` 在任何并行任务启动前检查整包摘要，并在每次实际规则调用前再次检查；revoked/rejected 以 `INVALID_EXTENSION` 拒绝，不调用规则、不恢复白名单。未知/待审摘要仍在隔离沙箱校验。已运行中的调用不能被应用层检查追溯撤回；后续调用会被阻断。

回归：`ImportQueueIntegrationTest.revokedAndRejectedExtensionsAreNeverRestoredByAdminUpload` 清缓存后验证 `runner.execute` 调用为 0、原审核状态不变、条目未通过。

### SR-04：正式配置可沿用已公开的开发默认凭据（中）

复现：provider 模式填齐 pepper 与 AI 主密钥，但省略 runner token / DB password，原应用仍采用仓库公开的开发默认值。

修复：新增 `DeploymentSecrets` 启动检查，只有 fake 且本地/测试 Origin 才允许开发默认值。其他配置拒绝空白或仓库默认 runner token/数据库密码，错误只包含环境变量名，不打印值。保留已有验证码与主密钥检查，不读取本机密钥。

回归：`DeploymentSecretsTest.developmentDefaultsRequireFakeModeAndLocalOrigin`、`productionRejectsEmptyOrDefaultSecretsWithoutPrintingThem`；集成测试的真实 Spring 上下文覆盖 Bean 被实际注册。此检查不证明用户自选凭据具有足够熵；生产应使用独立随机值。

> 后续（L8）：这项检查已合并进上线工程的生产配置启动检查 `ops.ProductionConfigCheck`（一次列出全部缺失项，并要求 runner 令牌 ≥ 32、数据库密码 ≥ 16 字符），`DeploymentSecrets` 与其测试删除，测试用例移到 `ProductionConfigCheckTest`（`developmentCredentialsRequireFakeModeAndLocalOrigin`、`blankOrDefaultCredentialsAreRejected`）。

### SR-05：截图授权分散在 Java，未核对当前审核/校验状态（中，边界加固）

复现：保留 approved_revision 指针，但该版本 review_status 已不再 approved（例如状态不一致）时，旧文件读取只验证包可见性，仍可能公开截图。常规流程中的待审新版本原本已有保护。

修复：`CatalogMapper.visibleFile` 一条 SQL 校验所有者/管理员或 public + active + 当前版本 + approved + validated；`CatalogPublishService.file` 直接调用。不把尚未审核的信息或任意原文件挂到匿名截图路由。

回归：`CatalogIntegrationTest.approvalRequiresApprovedExtensionsThenPublishesAndNotifiesThePublisher` 保留待审 IDOR 断言，新增当前指针未变化但审核变为 pending、包改为 private 时他人不能读，所有者仍能读。

### SR-06：L6 固定版本下载漏记每日下载数（低，统计一致性）

复现：`GET /api/packs/{id}/download?revision=…&sha256=…` 成功后 catalog_pack.download_count 增加，但 catalog_download_day 没有对应计数，后台近 7 天统计漏掉客户端导入流量。

修复：在 `LocalImportService.reserve` 的原有事务中，完成站点月预算 → IP 日预算 → 当前版本累计下载的条件更新后，再调用 `PlazaMapper.recordDownload`。日期与广场一致使用北京时间；不增加锁读。清单请求不算下载，预算/版本/参数拒绝不算下载。日计数写入失败时累计计数和预算一起回滚。

回归：`ClientContractIntegrationTest.pinnedDownloadHandsTheExactBlobToNginxAndCountsIt`、`importParametersAreStrictAndErrorsUseTheLocalImportShape`、`downloadBudgetsAndRateLimitsFailBeforeCounting` 增加日统计断言；新增 `dailyDownloadFailureRollsBackTotalAndTraffic`、`concurrentPinnedDownloadsCountEveryAcceptedRequestExactlyOnce`（8 路门控并发）、`pinnedDownloadUsesSameBeijingDateBoundaryAsPlaza`（可控时钟跨北京零点，两种路由写入同一日统计）。口径为服务器接受下载并授权 Nginx 传输一次，不代表浏览器一定完整接收；与广场相同，不按网络重试去重。

### SR-07：设备兑换与失效操作的锁顺序相反（中）

证据：旧兑换顺序“DELETE auth_connect_code → 锁 account_user”，封禁/密码重置为“锁 account_user → 删除授权码”，并发时存在死锁环。

修复：兑换也先锁已普通读取出的既有账号，再删除授权码；不锁不存在的授权码，不改变一次性、失败消费与 PKCE 语义。

回归：`ClientContractIntegrationTest.codeExchangeLocksAccountBeforeConsumingCode` 在真实兑换中验证锁调用先后；既有重复兑换、错误 verifier、封禁及设备并发约束测试一同运行。这是确定的锁顺序回归，不声称本机随机复现了 MySQL 死锁。

## 3. 路由/归属审查索引

| 领域与入口 | 服务/SQL 授权锚点 | 代表性回归范围 |
| --- | --- | --- |
| `/api/collections/bank/**`、exam、actions、summary/restart/jobs | PracticeReadMapper、PracticeContextMapper、QuestionStateMapper、ExamMapper；user_id、collection_id 及题目绑定 | PracticeReadIntegrationTest、DraftService/幂等测试、PracticeLifecycleIntegrationTest、ExamModeIntegrationTest |
| history、历史笔记/图片 | PracticeHistoryMapper、ContentAccessMapper；轮次 user_id、collection_id、未删除及 frozen | PracticeCompletionHistoryIntegrationTest |
| `/api/resources/{sha}`、白板 | UserResourceMapper、ContentAccessMapper、WhiteboardMapper；上传者或有权题库/历史引用 | WhiteboardResourceIntegrationTest、历史图片保留测试 |
| `/api/library/packs/**`、上传/删除/导入 | MyLibraryMapper.original、CatalogMapper.ownedActive、ImportCatalogMapper；所有者、版本、未删除 | MyLibraryIntegrationTest、ImportQueueIntegrationTest |
| catalog/plaza、收藏、评论举报 | PlazaMapper.VISIBLE、CatalogMapper.visibleFile、FeedbackMapper 的作者/举报人条件；管理处理统一管理员过滤链 | CatalogIntegrationTest、PlazaIntegrationTest、PackFeedbackIntegrationTest |
| notification | NotificationMapper.VISIBLE、BankOwnership 的用户范围；已读 INSERT SELECT 同一可见性条件 | NotificationIntegrationTest |
| AI balance/ledger、tasks | AiBalanceMapper.user_id、AiGradingMapper.user_id，提交/评分代次与题目内容指纹栅栏 | AiBalanceIntegrationTest、AiGatewayIntegrationTest、AiGradingIntegrationTest |
| L6 connect/devices/redeem/AI | PKCE、一次性授权码、DeviceMapper.active/owned、限频、AI 宿主生成 ref | ClientContractIntegrationTest；client-compat.spec.mjs |
| admin（账号、队列、审核、通知、AI、举报） | SecurityConfig 的统一管理员检查；审计 actor 来自会话 | AdminConsoleIntegrationTest、各领域管理测试 |

内部不带归属的内容主键查询、任务领取、审核与审计查询，不直接作为用户读取接口；调用前必须经过上述受限查询。这里没有把它们误计为“每条 SQL 都含 user_id”。接口矩阵是静态追踪加代表性动态测试，未为每条 HTTP 路由生成独立渗透用例。

## 4. 密钥与部署检查范围

对基础提交所在仓库完整可达历史（非 shallow，394 个提交）去重扫描：1,989 个 blob、24,485,167 字节，其中 1,936 个文本、53 个二进制。检查私钥 PEM、AWS/Tencent、GitHub、常见 sk-provider token 形状及长字面量凭据；排除显式 fake/example/your 占位和低字符多样性测试字面量，未发现候选。扫描只输出规则名、文件、行号和 blob ID，不输出值；脚本/明细在 git 忽略的 `.artifacts/local/security-review/`，不提交真实数据。新增改动另经人工检查。

这不是“仓库历史绝对没有秘密”的证明：不解码二进制，不覆盖已不可达对象或未知令牌格式；不读取作者 deploy/.env、SSH 配置或真实 provider Key，因此不验证实际部署凭据是否有效、是否曾泄露。runner/Prometheus/管理端口不发布到宿主，Nginx 明确拒绝 actuator 和 runner metrics 外部路径；runner 的专用网络 internal，blobs 只读。

## 5. 尚需作者决定 / 上线部署工作

1. **廉价写接口统一限频**：草稿、白板、收藏、标记通知等没有统一的“每用户每分钟”阈值，当前依靠业务大小限制、乐观锁/幂等及部分领域限频。白板约 250 ms 自动保存，不宜自行套用普通表单频率。建议另定分接口的持续速率、突发量及多实例共享后再实施；本 PR 没有擅自设定产品阈值。不是已证明的越权或免费 AI 漏洞。
2. **生产部署凭据与网络**：MySQL root 密码仍须由 L7 生产部署配置覆盖，应用不读取或持有 root 凭据；provider 的实际短信/邮件/人机验证配置需上线演练。未配置的通道失败关闭，不用 fake 回退。正式应用现已拒绝默认业务 DB 密码/runner token；生产仍应有独立 Compose、HTTPS、备份和防火墙验收。
3. **在途与已交付代码的边界**：撤销阻止后续页面/SDK/规则请求，但无法从一个已经打开、断网的浏览器收回此前交付的 JavaScript，也不能追溯中断已发出的沙箱调用。若要求实时强制终止所有已打开页面，需要额外的通知/心跳与 runner 取消协议，不能靠 HTTP 授权检查声称已实现。
4. **历史原样保留与停用提示**：按作者 2026-11-05 最新决定，停用拓展后该题位置仅显示历史丢失提示、不显示通用视图，数据不删除，重新批准后恢复；已实现，不再作为待决问题。未知拓展在导入沙箱运行属于既有导入设计，浏览器和练习路径仍只执行已批准摘要。
5. **单应用实例约束**：图片/L6 限频、AI 网关并发仍为既有进程内计数；当前单 app 部署有效，多实例会按实例放大额度。扩容前必须迁到共享计数，不把它们描述为跨节点全局保护。数据库上传日限额、配额、任务租约与扣费幂等不依赖这些内存计数。
6. **下载预算覆盖范围**：`qf.client.monthly-budget-bytes` / IP 日预算目前仅约束 L6 固定版本下载；普通广场 `/api/plaza/packs/{id}/archive` 与所有者原文件下载不接这套预算。因此它不能被当成“全站所有路径的带宽硬上限”。若要防止通过其他原文件路由绕过带宽预算，应另行确认统一预算/边缘限速策略；本 PR 只按明确要求统一下载统计，不擅自恢复已经取消的用户每日下载额度。

## 6. 验证与文件清单

本地后端使用 JDK 21、Testcontainers MySQL 8.4、真实 runner（AI 使用 FakeUpstream）。第一轮相关测试 106 项，发现 1 个旧占位脚本断言与新规则不一致，已更新；最终定向 `mvn verify` 39 项、0 失败/错误。另补北京日界线与默认用户配置的检查；补测和完整 CI 的最终数量记录在 PR 描述中。CI 必须 Backend、Frontend、Rule runner、Compose smoke 四项通过才开非草稿 PR。

按 2026-11-05 补充决定调整后，前端构建与 173 个 Node 测试通过（包括仅提示、纯文本和白板恢复测试）。桌面/手机生命周期浏览器测试增加实际撤销/恢复步骤，截图输出到被忽略的测试产物目录，由 Compose smoke 验证。没有更改数据库结构，无 Flyway 新迁移，没有修改 SubmitService、DraftService、IdempotentDraftService 或 QuestionStateMapper 的并发逻辑。

另关闭未使用的 Spring 默认 UserDetailsService 自动配置，避免每次启动生成并打印默认密码；没有开启 Basic/form 认证，也没有改变会话/设备认证。`SecurityIntegrationTest.noGeneratedPasswordUserIsRegistered` 验证无默认用户 Bean。

生产文件：`QuizForgeApplication`、`AccountService`、`DeviceService`、`LocalImportService`、`DeploymentSecrets`；`CatalogMapper`/`CatalogPublishService`；`QueuedImportValidation`；`ContentAccessMapper`/`ContentPages`；`PracticeHistoryService`；`frontend/practice/app.js`、`practice/practice-context.js`、`history/history-view.js`、新增 `history/unavailable-question.js`。

测试文件：`AccountCompletionIntegrationTest`、`ClientContractIntegrationTest`、`SecurityIntegrationTest`、`DeploymentSecretsTest`、`CatalogIntegrationTest`、`ImportQueueIntegrationTest`、`PracticeCompletionHistoryIntegrationTest`、`frontend/test/history-unavailable.test.mjs`、`frontend/test/browser/practice-lifecycle.spec.mjs`。文档：本报告、`docs/plan/02-practice-acceptance.md` §6。
