# 本地压测准备

只用于开发机的隔离 Compose 项目，不得将 BASE_URL、抓取目标或 Docker context 指向远程服务器。这里只提供造数和单用户连通性检查，正式 k6 场景由作者编写。需要本地 Docker Compose **2.24.4+**（使用 `!override`）、Python 3、可用的 Docker 内存至少 10 GiB；无需安装 k6。监控和负载生成器在被测 4 核/8 GiB 之外，还会消耗开发机资源；本次冒烟不构成容量结论。

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

生成 `loadtest/users.json`，格式为数组：`[{email,password,collections:[{id,title,questionIds:[...]}]}]`。包含明文测试密码，已加入 gitignore，不上传到 PR 或制品。每个用户有五本基础题型题库。重复执行时使用文件中的账号密码，已有且仍可访问的实例不重复导入；账号密码同时保存在同目录的 `users.seed-state.json` 检查点中（同样已忽略）；人数减少时仍保留其他账号的密码。不要删除这些文件后对同一个数据库重跑（会丢失随机密码）。从头重建时同时使用全新 Compose 项目和新 `--output` 文件，或只删除自己的隔离项目卷及造数文件。中断时已完成的账号会保存在检查点中。

参数 `--users N` 指定本次输出人数。默认输出位置固定，切换项目时也应切换 `--output`；k6 则需将所用文件复制为其挂载目录的 users.json。脚本默认严格串行，避免把造数阶段当作压测。测试账号使用 `loadtest-0000@example.test` 等固定名称。

## k6 冒烟与后续场景

```powershell
docker compose @compose --profile tools run --rm k6 run smoke.js
# 作者写好自己的场景后，同样通过固定版本的 grafana/k6 容器运行：
# docker compose @compose --profile tools run --rm k6 run author-scenario.js
```

`smoke.js` 固定 1 VU、1 次迭代，只登录并读取 users.json 第一人的第一道题；校验全部成功且 HTTP 错误率为 0。BASE_URL 默认是本地 Compose 的 `http://nginx`，Origin 取上述 QF_PUBLIC_ORIGIN。Cookie 在正式接口中带 Secure；本地 HTTP 冒烟客户端显式复制登录响应中的 Cookie 到读题请求头，不改变服务端 Cookie 安全属性。不自动跟随重定向。脚本只允许 Compose nginx 或 loopback 地址。

## Grafana 与指标

打开 <http://localhost:3000/d/quizforge-loadtest>，账号 `admin`，默认密码 `loadtest-local-only`（可通过 QF_GRAFANA_PASSWORD 替换）。面板和 Prometheus 数据源自动预置；Prometheus 在 <http://localhost:9090/targets> 应显示 quizforge 为 UP。端口可通过上述环境变量调整。

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

P95/P99 用 `histogram_quantile(0.95/0.99, sum by(le,uri,method)(rate(http_server_requests_seconds_bucket[1m])))`；runner 按 op 聚合。HTTP 和 runner 直方图边界范围配置为 1ms..120s，超过上界的观测仍进入 +Inf 桶。错误率面板分别显示 5xx、4xx+5xx；业务规则的 HTTP 422 会计入后者。进入 Controller 前即被安全过滤器拒绝的请求可能以 UNKNOWN 为 uri；面板保留该标签，不使用原始 URL 制造高基数。5 秒抓取，建议至少等待两次抓取再看区间 rate。单次冒烟或没有请求时，部分图表为空/NaN 属正常，不将“无数据”画成 0。

官方配置依据：[Spring Boot metrics](https://docs.spring.io/spring-boot/4.1/reference/actuator/metrics.html)、[Micrometer cache binding](https://docs.micrometer.io/micrometer/reference/reference/cache.html)、[Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/)、[k6 Cookie API](https://grafana.com/docs/k6/latest/using-k6/cookies/)。依赖版本由项目 Spring Boot 4.1.2 BOM 管理。

## 可复现资源边界与收尾

| 被测服务 | CPU quota | 内存硬上限 |
| --- | --- | --- |
| app | 1.5 核 | 3 GiB（JVM Xms512m、Xmx2g、ActiveProcessorCount=2） |
| mysql | 1 核 | 3 GiB |
| rule-runner | 1.25 核 | 1.5 GiB |
| nginx | 0.25 核 | 0.5 GiB |
| 合计 | 4 核 | 8 GiB |

Prometheus、Grafana 各 0.5 核/512 MiB，k6 1 核/512 MiB，不计入被测机配置。quota 是上限，不代表独占物理核；记录主机 CPU、Docker 可用资源、镜像 digest、提交号、人数、缓存冷热、连接池和 runner 并发配置，才能比较结果。镜像固定版本；保留卷会保留缓存以外的业务数据和监控历史，重启 app 会清空 JVM 投影缓存。

```powershell
docker compose @compose down
# 仅当明确要清除自己的压测数据时才使用 down -v，并删除对应 users.json。
```

CI 不运行压测或 seed；原有四个 job 继续运行。新增测试验证内部指标端口、HTTP 直方图、连接池/缓存导出、runner 计时/占用/拒绝/两类超时。这里的 k6 冒烟仅证明链路连通，不能报告吞吐量或容量上限。
