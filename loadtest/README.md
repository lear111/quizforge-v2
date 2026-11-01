# 本地压测准备

只用于开发机的隔离 Compose 项目，不得将 BASE_URL、抓取目标或 Docker context 指向远程服务器。这里只提供造数、单用户连通性检查及可选的短时 profiling 冒烟，正式 k6 场景由作者编写。需要本地 Docker Compose **2.24.4+**（使用 `!override`）、Python 3、可用的 Docker 内存至少 10 GiB；无需安装 k6、Java 或 profiler。监控和负载生成器在被测 4 核/8 GiB 之外，还会消耗开发机资源；本次冒烟不构成容量结论。

## 启动

在仓库根目录使用一个全新的、名称以 `qf-loadtest` 开头的项目。不要复用日常开发数据库。

```powershell
# PowerShell；下面其他命令在同一个窗口运行。已有服务占用 8088 时改用空闲端口。
$env:QF_HTTP_PORT='8099'
$env:QF_PUBLIC_ORIGIN='http://localhost:8099'
$env:QF_PROMETHEUS_PORT='9090'
$env:QF_GRAFANA_PORT='3000'
$project='qf-loadtest'
$compose=@('-p',$project,'-f','deploy/docker-compose.yml','-f','deploy/docker-compose.loadtest.yml')
docker compose @compose up -d --build
Invoke-RestMethod http://localhost:8099/api/health
```

等待 health 返回成功再造数。所有公开端口只绑定 127.0.0.1。Prometheus/Grafana 同时加入 UI 网络以支持 Docker Desktop 的本机端口转发；到 app 的抓取仍经 internal monitoring 网络，未配置任何远程抓取或 remote_write，Grafana 的遥测、更新、预装插件与公钥在线检索均关闭。app 的管理端口 9091 **没有宿主机端口映射**；Prometheus 在 internal monitoring 网络访问 `http://app:9091/actuator/prometheus`。Nginx 对 `/actuator` 和其子路径返回 404。其他环境的管理端口默认绑定 127.0.0.1，只暴露 prometheus 端点。

```powershell
# 应返回 404，而非指标正文
curl.exe -i http://localhost:8099/actuator/prometheus
```

## 造数（默认 100 用户）

```powershell
python loadtest/seed/seed.py --project $project --base-url http://localhost:8099
# 只做连通性检查可先用 --users 1
```

脚本通过 HTTP 登录/注册，上传 `samples/basic-types.qbank`，再用同一 Compose 项目的 MySQL 执行样例拓展审批 SQL（reviewer=loadtest-fixture）。只批准这些实例引用的摘要；这是压测白名单夹具，不是审核流程。脚本会拒绝远程 URL、HTTP 重定向、远程 Docker 连接、没有 `qf.loadtest=true` 标签的 MySQL，以及不匹配该项目 Nginx 映射端口的 URL。

生成 `loadtest/users.json`，格式为数组：`[{email,password,collections:[{id,title,sample,questionIds:[...],answers:{题目ID:作答对象}}]}]`。包含明文测试密码，已加入 gitignore，不上传到 PR 或制品。每个用户有五本基础题型题库和一本 `loadtest-300`（混合 300 题），共 305 道可见题。重复执行时使用文件中的账号密码，已有且仍可访问的实例不重复导入；旧版仅含五本基础题型的检查点会保留这些实例，只补导入压测题库。账号密码同时保存在同目录的 `users.seed-state.json` 检查点中（同样已忽略）；人数减少时仍保留其他账号的密码。不要删除这些文件后对同一个数据库重跑（会丢失随机密码）。从头重建时同时使用全新 Compose 项目和新 `--output` 文件，或只删除自己的隔离项目卷及造数文件。每次导入完成都会保存检查点。

`answers[qid]` 可直接放进动作请求的 `data.answer`，不是字符串化的 JSON。生成器读取 `.qbank` 内原始题目 data，以及 manifest 指定的 questionSchema / answerSchema；不从读题 API 获取标准答案。单选、多选和判断使用 data 中的正确答案，选项 ID 不硬编码；两种简答题按真实小题 ID 分别生成富文本或答题区文本。答题区还遵循固定上游 `rules.js` 的 `areaId()` / `height()` / `areaValid()`：ID 为 `response-<小题ID>`，高度取 data.answerHeight，省略时为 480；这些跨字段约束无法仅靠 JSON Schema 推断。未被集中大纲引用的题不输出作答。不支持的 schema 明确失败，不猜测合法答案。这里只适配固定来源的五种基础拓展，不是通用的 JSON Schema 作答生成器。

参数 `--users N` 指定本次输出人数。默认输出位置固定，切换项目时也应切换 `--output`；k6 则需将所用文件复制为其挂载目录的 users.json。脚本默认严格串行，避免把造数阶段当作压测。测试账号使用 `loadtest-0000@example.test` 等固定名称。

## k6 冒烟与后续场景

```powershell
docker compose @compose --profile tools run --rm k6 run smoke.js
# 作者写好自己的场景后，同样通过固定版本的 grafana/k6 容器运行：
# docker compose @compose --profile tools run --rm k6 run author-scenario.js
```

`smoke.js` 固定 1 VU、1 次迭代，只登录并读取 users.json 第一人的第一道题；校验全部成功且 HTTP 错误率为 0。BASE_URL 默认是本地 Compose 的 `http://nginx`，Origin 取上述 QF_PUBLIC_ORIGIN。Cookie 在正式接口中带 Secure；本地 HTTP 冒烟客户端显式复制登录响应中的 Cookie 到读题请求头，不改变服务端 Cookie 安全属性。不自动跟随重定向。脚本只允许 Compose nginx 或 loopback 地址。

### 作答正确性验证（会提交题目）

对全新、可丢弃的本地造数项目运行：

```powershell
python -B -m unittest discover -s loadtest/seed -p 'test_*.py'
python loadtest/seed/verify_answers.py --base-url http://localhost:8099
# 自定义 seed --output 时，传同一 --users-file；默认只验证第一位用户。
```

验证脚本对该用户的全部 305 个 answers 逐个读题、HTTP 保存草稿、HTTP 提交，要求两个写操作均为 200；303 道客观题必须取得题库 data 声明的满分。普通简答题保持拓展的 graded/0，答题区简答保持 pending/null。它会消耗这位用户的未提交状态，不要在准备正式测量的账号上运行；再次验证应换新项目/账号。seed 重跑只复用题库实例，不重置已提交状态。该脚本是串行正确性测试，不是 k6 场景或容量测试；CI 只运行无网络的 Python 单元测试，不运行造数或 HTTP 写入验证。

## Grafana 与指标

打开 <http://localhost:3000/d/quizforge-loadtest>，账号 `admin`，默认密码 `loadtest-local-only`（可通过 QF_GRAFANA_PASSWORD 替换）。面板和 Prometheus 数据源自动预置；Prometheus 在 <http://localhost:9090/targets> 应显示 quizforge 为 UP。端口可通过上述环境变量调整。

Runner 新增抓取目标 `rule-runner:8090/v1/metrics/prometheus`，也应为 UP。仅 loadtest overlay 将 runner 加入 `internal: true` 的 monitoring 网络，未发布端口；Nginx 明确对该路径返回 404。Prometheus 按[官方 HTTP 配置](https://prometheus.io/docs/prometheus/latest/configuration/configuration/#http_config)通过 `http_headers.X-Internal-Token.files` 读取 `/etc/prometheus/runner-token`；Compose config 内容来自与 app/runner 相同的 `QF_RULE_RUNNER_TOKEN`（含开发环境默认值），更换令牌后重新创建服务即可。不要把含令牌的 `docker compose config` 完整输出上传。

新增四个面板：按 op 的 runner **排队 vs 执行 P95**、**进程启动次数/分钟**、**队列长度与当前进程数**、**按原因的停止次数/分钟**。启动/停止用 `increase(...[1m])`，直方图用与现有图表相同的 `$__rate_interval`。LRU 停止与启动同时频繁出现可作为抖动证据；执行阶段仍包含冷启动，不能直接当成钩子计算耗时。排队直方图只包含实际分配过 worker 的请求，未获分配便超时/拒绝/关闭的请求不进入该直方图。

| Prometheus 名称 | 含义与标签 |
| --- | --- |
| `http_server_requests_seconds_bucket/count/sum` | 默认 HTTP Timer；按 `uri` 路由模板、method、status 等区分，直方图供 P95/P99。不要用真实题目 ID 作标签。 |
| `hikaricp_connections_active/idle/pending/max/min` | HikariCP 使用中、空闲、等待和配置连接数，标签 pool。其余默认 Hikari 指标也保留。 |
| `qf_rule_runner_duration_seconds_bucket/count/sum` | 每次逻辑 execute 调用耗时，op 标签限定协议操作名，其他值合并为 other；含一次连接重试耗时及立即舱壁拒绝，不重复计算内部重试。 |
| `qf_rule_runner_bulkhead_active` | 当前已占用许可数，包括连接重试期间。 |
| `qf_rule_runner_bulkhead_limit` | 配置的最大并发许可数。 |
| `qf_rule_runner_bulkhead_rejected_total` | 未拿到许可、立即拒绝的累计次数。 |
| `qf_rule_runner_timeouts_total{op,source}` | 累计超时，source=http 表示 Java HttpTimeoutException（含连接超时），source=runner 表示 HTTP 200 的 RULE_TIMEOUT；每次逻辑调用最多记一次。没有发生过时该标签序列尚不存在。 |
| `cache_gets_total{cache="projection",result="hit"或"miss"}` | Caffeine 命中/未命中累计次数；面板用区间 rate(hit)/rate(hit+miss) 得命中率。 |
| `cache_size`、`cache_evictions_total` 等 | Caffeine 默认绑定器输出的缓存大小、淘汰等统计。 |
| `qf_runner_queue_wait_seconds_bucket/count/sum{op}` | runner 池内入队到分配 worker 的等待时间；单调时钟，按 op 区分。 |
| `qf_runner_execution_seconds_bucket/count/sum{op}` | 发给 worker 前到收到完整结果帧或活动任务被终止的时间；含 IPC、worker 内序列化、冷启动、VM/Schema 初始化及失败执行，不含主进程输入编码和成功响应的解析。 |
| `qf_runner_worker_starts_total` | 启动尝试累计次数；异步启动失败也记一次。 |
| `qf_runner_worker_stops_total{reason}` | 停止累计次数；lru、max_requests、timeout、abnormal_exit、idle、shutdown、output_limit，单进程只记一次。 |
| `qf_runner_workers`、`qf_runner_queue_length` | 池内当前工作进程数（含空闲）与等待分配的队列长度。 |
| `container_cpu_usage_seconds_total{project,service,id}` | Docker Engine 的 cpu_usage.total_usage 纳秒转秒；rate 后是消耗的 CPU 核数，不是占 quota 的百分比。 |
| `container_cpu_cfs_periods_total{project,service,id}` | Engine throttling_data.periods，CFS 调度周期累计数。 |
| `container_cpu_cfs_throttled_periods_total{project,service,id}` | Engine throttling_data.throttled_periods，至少发生过一次限流的周期数。 |
| `container_cpu_cfs_throttled_seconds_total{project,service,id}` | Engine throttling_data.throttled_time 纳秒转秒；可能跨多个运行线程累计，不能当作墙钟损失比例。 |
| `qf_container_collector_up{project}` | Docker API 版本与当前项目容器发现是否成功。 |
| `qf_container_stats_up{project,service,id}` | 该容器的 stats 请求是否成功；缺失或请求失败不会伪造 CPU/CFS 样本。 |
| `qf_container_stats_throttling_available{project,service,id}` | 是否包含全部三个 CFS 计数器；不支持时为 0，CFS 图表为空而非显示“未限流”。 |

P95/P99 用 `histogram_quantile(0.95/0.99, sum by(le,uri,method)(rate(http_server_requests_seconds_bucket[1m])))`；runner 按 op 聚合。HTTP 和 runner 直方图边界范围配置为 1ms..120s，超过上界的观测仍进入 +Inf 桶。错误率面板分别显示 5xx、4xx+5xx；业务规则的 HTTP 422 会计入后者。进入 Controller 前即被安全过滤器拒绝的请求可能以 UNKNOWN 为 uri；面板保留该标签，不使用原始 URL 制造高基数。5 秒抓取，建议至少等待两次抓取再看区间 rate。单次冒烟或没有请求时，部分图表为空/NaN 属正常，不将“无数据”画成 0。

官方配置依据：[Spring Boot metrics](https://docs.spring.io/spring-boot/4.1/reference/actuator/metrics.html)、[Micrometer cache binding](https://docs.micrometer.io/micrometer/reference/reference/cache.html)、[Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/)、[k6 Cookie API](https://grafana.com/docs/k6/latest/using-k6/cookies/)。依赖版本由项目 Spring Boot 4.1.2 BOM 管理。

## 容器 CPU 与限流观测

压测 overlay 使用等效的轻量 `container-metrics` exporter，而非 cAdvisor。它通过本机 Linux Docker Engine 的 Unix socket，只调用 GET `/version`、`/containers/json`、`/containers/{id}/stats?stream=false&one-shot=true`，动态协商 API 版本；按 Compose project 标签筛选后再次核对，仅采集 **app/mysql/rule-runner/nginx**。没有远程 Docker endpoint 配置，不读取容器环境变量，不控制容器。Prometheus 在 internal monitoring 网络抓取 `container-metrics:9404/metrics`，不发布端口；`containers` target 应为 UP，collector/stats/throttling_available 三类指标应为 1。

这避免了 cAdvisor 在 Desktop VM 中的宿主根目录/cgroup 挂载差异。本次选择等效方案，没有据此声称 cAdvisor 不兼容。Docker socket 的 `:ro` 只保护挂载文件，**不限制 Engine HTTP API 的写方法**；因此仅在本机可丢弃环境使用这个受信任的 GET-only exporter，不把 socket 或端口暴露到外网。此镜像因 socket 权限使用 root，drop 全部 capabilities、只读根文件系统；app 仍为非 root。不要复用到生产部署。

新增“各容器 CPU 使用量（核）”与“各容器 CFS 限流周期比例”图表。后者为 `rate(throttled_periods)/rate(periods)`：例如 0.4 表示 40% 的调度周期发生过限流，**不是损失 40% 的 CPU**。重建容器后 id 标签变化，Prometheus 能处理每个容器计数器的重置；不能跨 id 先求和再 rate。刚启动或没发生有效周期时可能为 NaN。

如果该 Engine 不提供 `throttling_data`，先检查 `qf_container_stats_throttling_available`，不要从 `docker stats` 的 CPU% 推断限流。可用下面的本机对照方式；cgroup v2 中 `nr_periods/nr_throttled/throttled_usec` 分别对应上面三个 CFS 计数器（时间单位微秒），同一录制前后各取一次：

```powershell
docker compose @compose exec -T app cat /sys/fs/cgroup/cpu.stat
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}'
```

`docker stats` 仅是使用量快照，不能替代 CFS 指标。Windows/WSL2 的 Linux 容器运行在 Docker Desktop VM 内，宿主任务管理器不是容器 CPU quota 的观测点。本地 Engine 的 API 定义见 [Docker Engine container stats](https://docs.docker.com/reference/api/engine/version/v1.51/#tag/Container/operation/ContainerStats)。

## 按需 JFR 与 HTML 火焰图

仅压测 overlay 选择 `backend/Dockerfile` 的 `profiling` target（Temurin 21 **JDK**，带 jcmd/jfr）；默认生产构建仍使用最终 JRE stage。app 的 entrypoint、堆和 CPU quota 不变，额外预设 FlightRecorder 的 stackdepth=128。**没有 `StartFlightRecording` 参数，也不会自动录制。** `/opt/qf-profile.jfc` 基于 JDK 的 profile 配置，ExecutionSample 间隔 10ms，关闭 NativeMethodSample（避免把 native 中等待的线程混入 Java CPU 图），ObjectAllocationSample 启用 stackTrace，throttle=150/s。录制有开销，开启/未开启的数据应分开记录。

在上面启动环境的同一个 PowerShell 窗口运行；jcmd 与 app 同为 uid 10001，无需宿主 Java：

```powershell
# 初始应为 No available recordings；app 中 Java 是 PID 1。
docker compose @compose exec -T app jcmd 1 JFR.check
# 开始录制，限制磁盘缓冲大小/最长保留时间；不会自动结束。
docker compose @compose exec -T app jcmd 1 JFR.start name=qf settings=/opt/qf-profile.jfc disk=true maxsize=256m maxage=5m
# 此时运行压测；要中途导出且继续录制可执行：
# docker compose @compose exec -T app jcmd 1 JFR.dump name=qf filename=/tmp/qf-profile-part.jfr
# 停止并导出，放 /tmp 避免绑定目录的 uid 权限差异。
docker compose @compose exec -T app jcmd 1 JFR.stop name=qf filename=/tmp/qf-profile.jfr
New-Item -ItemType Directory -Force loadtest/artifacts | Out-Null
docker compose @compose cp app:/tmp/qf-profile.jfr loadtest/artifacts/app.jfr
docker compose @compose exec -T app jfr summary /tmp/qf-profile.jfr

# 固定 async-profiler v4.2 的独立 Java converter，构建时校验官方 release SHA-256。
docker compose @compose --profile tools build jfr-converter
docker compose @compose --profile tools run --rm jfr-converter --state runnable --norm --dot --reverse app.jfr cpu.html
docker compose @compose --profile tools run --rm jfr-converter --alloc --total --norm --dot --reverse app.jfr alloc.html
Start-Process loadtest/artifacts/cpu.html
Start-Process loadtest/artifacts/alloc.html
```

转换容器 `network_mode: none`，只读录制文件并向 artifacts 目录写 HTML；CPU 图按方法聚合所有线程（没有 `--threads` 分拆）；`--reverse` 将叶方法合并后展开调用者，便于直接看到跨调用链的热点，`--alloc --total` 显示采样估算的分配字节热点。这里必须用 `--state runnable`：v4.2 的 `--cpu` 筛选 async-profiler 的 STATE_DEFAULT，而 JDK 的 ExecutionSample 是 STATE_RUNNABLE，直接使用会得到空图（见 [JfrConverter.getThreadStates](https://github.com/async-profiler/async-profiler/blob/v4.2/src/converter/one/convert/JfrConverter.java#L109-L117)）。本配置关闭 NativeMethodSample 后，runnable 图只统计 Java ExecutionSample；不要直接套用到包含 native/wall 事件的其他录制。图中可点击栈缩放、搜索方法；分配图不代表存活堆或泄漏。JDK JFR 的 ExecutionSample 是 Java 执行采样，不能把它当作完整的 native/off-CPU 分析；未被采样的代码不会出现。原始 JFR 可能含环境/请求内容，users.json 含密码，`loadtest/artifacts/` 整体 gitignore，不上传原始录制或账号文件到 PR。官方工具依据：[JDK 21 jcmd](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html)、[jfr configure](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html)、[async-profiler v4.2 converter](https://github.com/async-profiler/async-profiler/blob/v4.2/docs/ConverterUsage.md)。

### 20 VU / 60 秒测量链路冒烟

新建一个未被使用的 `qf-loadtest-profiling` 项目（将启动段 `$project` 改为此值；端口也选空闲值），不要复用正式压测账号或已提交题目。此脚本会执行真实的 overview/read/draft/submit，不是容量基准。

```powershell
python loadtest/seed/seed.py --project $project --base-url $env:QF_PUBLIC_ORIGIN --users 20 --output loadtest/artifacts/users.json
# 等待 Prometheus 至少两次抓取后，按上面命令 JFR.start。
$usersPath=(Resolve-Path loadtest/artifacts/users.json).Path
docker compose @compose --profile tools run --rm -v "${usersPath}:/work/users.json:ro" k6 run profiling/smoke.js
# 再执行 JFR.stop、cp 和两个 converter 命令。
# Grafana 应出现 CPU 核数、CFS 周期比例，Prometheus 三个 target 都 UP。
docker compose @compose down
```

默认为 20 VU/60s，setup 阶段串行登录（避免无关的 scrypt 舱壁突发），每个 VU 使用独立账号，每轮使用新的客观题；`VUS`/`DURATION` 可通过 compose run `-e` 调整，账号数量不得小于 VU。脚本没有远程 JS import，拒绝非本机 URL，禁止重定向；本地 HTTP 只显式重放 Secure Cookie，不改服务端属性。每人最多 300 轮，耗尽或遇到已提交题会失败，重跑请换全新项目/夹具。不对延迟设容量阈值，仅检查 HTTP 200 与客观题满分。

## 可复现资源边界与收尾

| 被测服务 | CPU quota | 内存硬上限 |
| --- | --- | --- |
| app | 1.5 核 | 3 GiB（JVM Xms512m、Xmx2g、ActiveProcessorCount=2） |
| mysql | 1 核 | 3 GiB |
| rule-runner | 1.25 核 | 1.5 GiB |
| nginx | 0.25 核 | 0.5 GiB |
| 合计 | 4 核 | 8 GiB |

压测基线固定 **QF_RULE_WORKER_LIMIT=8**；第三轮在此值下 LRU 抖动已消除，本任务保留该基线，以避免把重复冷启动混入 app CPU 分析。不改生产 compose 的默认值，也不改变池调度或 Java 舱壁。Prometheus、Grafana 各 0.5 核/512 MiB，container-metrics 0.25 核/128 MiB，k6 1 核/512 MiB，转换器 1 核/1 GiB，不计入被测机配置；转换在压测后进行。quota 是上限，不代表独占物理核；记录主机 CPU、Docker 可用资源、镜像 digest、提交号、人数、缓存冷热、连接池和 runner 并发配置，才能比较结果。镜像固定版本；保留卷会保留缓存以外的业务数据和监控历史，重启 app 会清空 JVM 投影缓存。

```powershell
docker compose @compose down
# 仅当明确要清除自己的压测数据时才使用 down -v，并删除对应 users.json。
```

CI 不运行压测或 seed；原有四个 job 继续运行。新增测试验证内部指标端口、HTTP 直方图、连接池/缓存导出、runner 计时/占用/拒绝/两类超时。这里的 k6 冒烟仅证明链路连通，不能报告吞吐量或容量上限。
