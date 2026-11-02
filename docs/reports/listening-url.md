# HTTPS 网络听力材料实现与验证

## 范围

依据格式 4 v0.8 §4.2 / §4.2.1，实现导入、宿主播放、历史保留与格式 3 转换。无数据库迁移、无下载代理、无拓展网络权限变更。网络音频不写 BlobStore、不增加远端音频大小；原 URL 作为 bank.json 元数据参与内容身份。

## 文件清单

- `.github/workflows/ci.yml`
- `backend/pom.xml`
- `backend/src/main/java/cloud/treenewbee/quizforge/content/domain/BankStructureRules.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/content/domain/NetworkAudioUrl.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeHistoryService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeReadService.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/content/domain/NetworkAudioPackageTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/content/domain/NetworkAudioUrlTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/application/PracticeCollectionSnapshotTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/application/PracticeCompletionHistoryIntegrationTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/web/PracticeReadIntegrationTest.java`
- `deploy/docker-compose.audio-test.yml`
- `docs/design/02-api.md`
- `docs/design/07-frontend-api-map.md`
- `docs/reports/listening-url.md`
- `frontend/UPSTREAM.md`
- `frontend/playwright.config.mjs`
- `frontend/practice/listening/listening-player.js`
- `frontend/practice/listening/listening.css`
- `frontend/practice/listening/network-audio.js`
- `frontend/test/audio-server/Dockerfile`
- `frontend/test/audio-server/fixture.py`
- `frontend/test/audio-server/server.py`
- `frontend/test/browser/listening-url.spec.mjs`
- `frontend/test/fixtures/network-audio-urls.json`
- `frontend/test/network-audio.test.mjs`
- `tools/qbank-upgrade/test_upgrade.py`
- `tools/qbank-upgrade/upgrade.py`

## 地址与下载

- Java 使用 `io.github.stephanebastian:whatwg-url:0.8.8`，浏览器使用原生 URL；两端共享测试向量。依赖版本已核对 [Maven Central](https://repo.maven.apache.org/maven2/io/github/stephanebastian/whatwg-url/maven-metadata.xml)；[官方源码](https://github.com/stephanebastian/whatwg-url)。库解析不请求网络。
- 该库对部分 IPv6 段存在序列化符号扩展问题：`2001:4860:4860::8888` 被输出为 `2001:4860:4860::ffff8888`。保留 WHATWG 解析，IPv6 字面主机单独按最长零段压缩规则规范化；仅将已限定字符集的 IPv6 字面量交给 InetAddress，不进行域名解析。共享向量覆盖此回归。
- 拒绝私有/回环/链路本地/组播/保留 IPv4，以及 IPv6 非全球单播、协议保留段、文档段、6to4 与映射 IPv4；不在导入或浏览器中查询域名 DNS。
- 浏览器直接匿名 fetch，`credentials:omit`、`referrerPolicy:no-referrer`、`mode:cors`；直链不跟随跳转（`redirect:error`），避免跳转到未经校验的地址。错误提示 HTTP 无法访问、可能未允许跨域、非音频、超过上限。
- 先检查响应音频 MIME 与 Content-Length，再以实际字节流计数，完整成功后生成 Blob URL。上限 64 MiB，等于上限可用；未知长度也按流计数。切换、离开当前题目或销毁播放器会中止未完成下载并释放旧 Blob URL。
- 扩展 iframe 仍为原 sandbox，`connect-src 'none'`，题目投影只得到 materialId，不得到 URL。当前用户有权读取的题库元数据向宿主页提供 URL。

## 测试对应

| 规则 | 测试 |
| --- | --- |
| HTTPS、规范化、2048、凭据、片段、域名、IPv4/IPv6/映射地址 | NetworkAudioUrlTest；network-audio.test.mjs；共享 network-audio-urls.json |
| file/url 二选一、位置与材料 ID、本地/网络混用、原文与哈希 | NetworkAudioPackageTest |
| 元数据与无后端代理、题目投影不含 URL | PracticeReadIntegrationTest.networkListeningAddressIsHostOnlyAndNeverProxied |
| 快照原文、历史不受当前题库变更影响、列表仅摘要 | PracticeCollectionSnapshotTest.snapshotFreezesOriginalNetworkUrlWithoutLoadingBytes；PracticeCompletionHistoryIntegrationTest.networkListeningHistoryKeepsOriginalUrlAfterCurrentBankChanges |
| 转换保留 URL、转换示例、本地音频搬迁、二选一 | tools/qbank-upgrade/test_upgrade.py 新增用例 |
| 匿名下载、完整读取、进度、两种大小判断、取消、错误类型 | frontend/test/network-audio.test.mjs |
| 真实 HTTPS/CORS、WAV 解码和播放、切换/离开取消、重试、历史提示 | frontend/test/browser/listening-url.spec.mjs（桌面与 375px） |

## 本机复现

```powershell
$env:QF_HTTP_PORT='8107'
$env:QF_PUBLIC_ORIGIN='http://localhost:8107'
$env:QF_AUTH_RATE_LIMIT_PER_MINUTE='1000'
$env:QF_VERIFICATION_IP_LIMIT_10MIN='1000'
$env:QF_VERIFICATION_IP_LIMIT_DAILY='10000'
$env:QF_EMAIL_DAILY_LIMIT='10000'
docker compose -p qf-listening-url -f deploy/docker-compose.yml -f deploy/docker-compose.audio-test.yml up -d --build
cd frontend
$env:QF_COMPOSE_PROJECT='qf-listening-url'
$env:QF_BROWSER_BASE_URL='http://localhost:8107'
$env:QF_AUDIO_TEST_URL='https://audio.qf.test:8447'
$env:QF_READ_BROWSER_TESTS='1'
npx playwright test listening-url.spec.mjs
cd ..
docker compose -p qf-listening-url -f deploy/docker-compose.yml -f deploy/docker-compose.audio-test.yml down
```

音频服务只有本机回环端口，加入独立测试 bridge 网络，合成 WAV、不读取真实题库；证书/私钥在容器 tmpfs 生成。Docker Desktop 下 internal 网络的端口映射无法从宿主访问，因此测试网络使用独立 bridge；测试服务不发起外部请求，正式 rule-runner 的 internal 隔离不变。Chromium 测试启动参数将 audio.qf.test 映射到 127.0.0.1，且仅测试时忽略此自签证书。截图与 trace 放在被忽略的 `.artifacts/`。

## 验证结果

- 本机相关 Maven verify：83 项，失败 0、错误 0、跳过 0（真实 MySQL + rule-runner 的新增用例在内）；另一次解析器回归验证 152 项全通过。
- 前端 build + 单元测试：157 项通过。
- Python 转换工具：32 项通过。
- 真实浏览器：桌面 1440×960 与手机 375×812，共 6 项通过（1.8 分钟），覆盖真实 WAV 播放、进度、匿名请求、切换/离开取消、CORS、两类超限、重试与历史。截图只在 `.artifacts/local/listening-url/screenshots/`，不提交。
- 首轮 CI Backend 全量 verify：3356 项，失败 0、错误 0、跳过 3（沿用的禁用缺陷复现）；迁移工具 17 项通过；Frontend、Rule runner 通过。首轮 Compose 的 6 个新增听力用例因 internal 测试网络无法映射宿主端口而失败，既有 22 项通过；修正测试网络和观察器后重新执行四项 CI，最终运行链接与结果见 PR。

## 当前功能边界

- 本机验收首次完整 Docker 构建在 Maven go-offline 依赖预取阶段长时间停滞；后续用已通过本机 verify 的 JAR + 相同非 root JRE 镜像启动独立 Compose。正式 Dockerfile 的完整构建由 CI 验证。
- 本机浏览器曾在听力加载之前触发既有题型页 10 秒注册超时。未修改非听力的 frame.js 计时逻辑、未增加固定 sleep 或自动重试；保留此环境现象供后续跟踪。测试观察方式已限定为宿主的原生媒体 load，不替换构造器或触碰沙箱。

- 当前 main 尚无宿主后端题库导出或考试接口，无法对不存在的接口做真实导出/考试闭环。本次不新增这些业务接口；保留完整 bank.json URL 与共用 collection_snapshot / listeningSource，历史闭环另有集成测试。后续实现导出/考试时应继续原样传递这些字段。
- 浏览器安全模型无法可靠区分网络故障与 CORS 拒绝，因此使用“可能未允许跨域”。不承诺网络材料离线可用，也不保证历史 URL 的内容不变化。
- 转换工具只检查 file/url 二选一及 URL 字符串，原文保留，不下载、不自行模拟 WHATWG；最终完整地址校验由 V2 导入器执行。
