# 上线前容量复测（真实 4 核 8G 服务器）

由作者在**正式服务器上线之前**执行：服务器已按 `docs/ops/01-deploy.md` 第 2 节第 1～6 步准备好，但还没有切换域名、没有真实用户。
复测使用一次性的 `qf-loadtest-prod` 项目（自己的数据库与数据卷），结束后整个删除，不影响正式项目 `quizforge` 的数据。
本机只验证过脚本能跑通（2 个用户、1 分钟），**没有任何容量结论**。

## 1. 测什么

| 场景 | 内容 | 来源 |
| --- | --- | --- |
| 练习（主场景） | 100 个用户，1 分钟加压到 100 → 保持 3 分钟 → 30 秒减到 0；每轮：打开题库 → 读题 → 思考 3～8 秒 → 保存草稿 2 次 → 提交 → 看结果。80% 的迭代在 300 题题库上 | M3（`docs/notes/m3-loadtest.md` 第 0.2 节） |
| 浏览器判分 | 生产模式 `QF_GRADING_MODE=browser`：提交只锁定作答，再由客户端上报判分结果；k6 用 `browser-grading.js` 计算合成样例的结果后上报 | #116、ADR-011 |
| 导入期间的提交 | 加压完成后，另有 `IMPORT_USERS` 个用户每 20 秒上传一次 300 题题库并等待后台校验，与满负载的练习重叠 | L8 |

M3 当时的 k6 场景由作者编写、没有放进仓库；`capacity.js` 按 M3 笔记描述的场景重新实现，并加入后两项。
两次结果可以比较，但不完全相同（例如每轮多一次 `summary` 请求、草稿之间有 1～3 秒停顿）。

被测配置就是 `docker-compose.prod.yml`：内存上限、JVM 堆 2 GiB、rule-runner 4 个进程、连接池 20、没有 CPU 配额。
`deploy/docker-compose.capacity.yml` 只做了三处改动：Nginx 使用 HTTP 开发配置并只绑定 `127.0.0.1:8099`（seed.py 与 k6 只允许本机地址，不测 TLS）；
假验证码与放宽的频率限制（压测账号的注册不经过短信 / 邮件）；MySQL 带 `qf.loadtest` 标签（seed.py 只对它批准样例拓展）。

k6 与被测服务在同一台机器上（不经过公网，避免把网络延迟算进来），容器限制为 0.5 核；记录时单独写出它的 CPU。

## 2. 步骤

在服务器的仓库目录（`/opt/quizforge`）中执行。需要 `python3`（造数）。正式项目如果已在运行，先停止它（`qf down`，**不加 `-v`**），复测结束再启动。

```bash
# 1. 一次性配置（与正式 prod.env 无关，结束后删除）
cp loadtest/capacity.env.example loadtest/capacity.env
for v in QF_DB_PASSWORD QF_DB_ROOT_PASSWORD QF_RULE_RUNNER_TOKEN QF_EMAIL_CODE_PEPPER QF_SMS_CODE_PEPPER; do
  sed -i "s/^$v=\$/$v=$(openssl rand -hex 32)/" loadtest/capacity.env; done
sed -i "s#^QF_AI_MASTER_KEY=\$#QF_AI_MASTER_KEY=$(openssl rand -base64 32)#" loadtest/capacity.env

export P=qf-loadtest-prod
export CAP="--env-file loadtest/capacity.env -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml -f deploy/docker-compose.capacity.yml"

# 2. 启动并等待健康
docker compose -p $P $CAP up -d --build --wait
curl -fsS http://127.0.0.1:8099/api/health

# 3. 关闭注册验证码与人机验证（只在这个一次性项目中；seed.py 用密码直接注册）
docker compose -p $P $CAP exec -T mysql sh -c 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' \
  <<< "UPDATE auth_policy SET registration_code=FALSE, registration_captcha=FALSE, invitation_required=FALSE WHERE id=1;"

# 4. 造数：100 个练习用户 + 5 个导入用户（约 10～20 分钟）
python3 loadtest/seed/seed.py --project $P --base-url http://127.0.0.1:8099 --users 105

# 5. 开始记录资源（另开一个终端，复测结束后 Ctrl+C）。1 分钟加压阶段同时是预热，比较时看保持阶段
mkdir -p loadtest/artifacts
while true; do date -u +%FT%TZ; docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}'; sleep 5; done > loadtest/artifacts/stats.log

# 6. 主场景：100 用户 + 导入
docker compose -p $P $CAP --profile tools run --rm -e VUS=100 -e IMPORT_USERS=5 k6 run capacity.js 2>&1 | tee loadtest/artifacts/capacity-1.log
```

需要比较配置时（第 5 节），每次都**重新造数**（已提交的题只读不写，复用用户会让写入越来越少），每种配置运行 2～3 次（M3：同一配置 P95 有 20%～30% 波动）。
`docker compose -p $P $CAP down -v` 后从第 2 步重来即可。

可选：同时叠加 `-f deploy/docker-compose.monitoring.yml`（需要 `alertmanager.yml` 与 exporter 密码，见 `01-deploy.md` 第 7.2 节）看连接池、堆、规则排队等指标；
不叠加时，用下面的命令在复测结束时抓一次应用指标：

```bash
docker compose -p $P $CAP exec -T app bash -c 'exec 3<>/dev/tcp/127.0.0.1/9091; printf "GET /actuator/prometheus HTTP/1.0\r\n\r\n" >&3; cat <&3' \
  | grep -E '^(hikaricp_connections_(active|pending|max)|jvm_memory_used_bytes|jvm_gc_pause_seconds_(count|sum)|qf_rule_runner_bulkhead|qf_import_queue_length|process_cpu_usage)' > loadtest/artifacts/app-metrics.txt
```

结束：

```bash
docker compose -p $P $CAP --profile tools down -v      # 删除一次性项目的全部数据
rm loadtest/capacity.env loadtest/users.json loadtest/users.seed-state.json
qf up -d --wait                                       # 如果之前停止了正式项目
```

## 3. 记录哪些数据

把下表填进 `docs/notes/` 下的新笔记（例如 `l8-capacity.md`），与 M3 第 6 节对照。

| 类别 | 数据 | 从哪里来 |
| --- | --- | --- |
| 环境 | CPU 型号与核数、内存、磁盘类型（`lscpu`、`free -m`、`lsblk -d -o NAME,ROTA,SIZE`）、Docker 版本、提交号 | 服务器 |
| 请求 | overview / read / draft / submit / grading-report / summary 的中位数、P95、P99、最大值 | k6 汇总 |
| 判分 | `qf_submit_flow`（提交到判分结果上报完成）P95 | k6 汇总 |
| 导入 | `qf_import_duration` 中位数与 P95、完成数、失败数；导入进行时与未进行时提交 P95 的差别 | k6 汇总 |
| 错误 | `http_req_failed`、失败请求的状态码与错误代码 | k6 汇总与日志 |
| 吞吐 | 请求数 / 秒、迭代数 | k6 汇总 |
| 资源 | 各容器 CPU 峰值与保持阶段平均值、内存峰值（对照 prod compose 的上限）、k6 自身 CPU | `stats.log` |
| 应用 | 连接池 active / pending 峰值、堆使用峰值、GC 次数与总时长、规则舱壁拒绝数、导入队列峰值 | `app-metrics.txt` 或 Prometheus |
| 宿主机 | `uptime` 负载、`free -m` 剩余内存、是否有 OOM（`dmesg \| grep -i oom`） | 服务器 |

## 4. 达标标准

`capacity.js` 已把下面的阈值写进 `options.thresholds`，不达标时 k6 以非 0 退出：

| 指标 | 标准 | M3 第四轮（模拟配额） |
| --- | --- | --- |
| 100 用户、导入进行中：提交 P95 | < 1 秒 | 100 ms（没有导入） |
| 提交到判分完成 `qf_submit_flow` P95 | < 1.5 秒 | — |
| 草稿 P95 / 读题 P95 | < 500 ms | 21 ms / 29 ms |
| 打开题库 P95 | < 2 秒 | 54 ms |
| 失败率 | < 1% | 0 |
| 检查通过率 | > 99% | — |

另外人工确认：

- 没有容器 OOM 或重启（`docker ps` 的 Up 时间、`dmesg`）；内存峰值低于上限的 85%
- 连接池没有持续的 pending；规则舱壁没有拒绝
- 导入在 3 分钟内完成，且导入期间提交 P95 比没有导入时增加不超过一倍
- 宿主机保持阶段至少还有 10% 的 CPU 空闲（为备份、日志、突发留余量）

阈值是上线门槛，不是性能目标：低于阈值但远差于 M3 时，也要找原因再上线。

## 5. 根据结果调整什么

一次只改一项，改完重新造数再测（M3 经验第 2 条）。

| 现象 | 调整 | 位置 |
| --- | --- | --- |
| 规则排队时间长（`qf_runner_queue_wait_seconds`）、舱壁拒绝、worker 频繁启动 / LRU 淘汰 | rule-runner 进程数 4 → 6 → 8；同时把 rule-runner 内存上限按每进程约 150 MiB 增加 | prod compose 的 `QF_RULE_WORKER_LIMIT`（app 与 rule-runner 两处）、`mem_limit` |
| 连接池 pending > 0、提交 P95 随并发线性增长 | `QF_DB_POOL_SIZE` 20 → 30；MySQL `max_connections` 默认 151 足够 | prod compose 的 app 环境 |
| 堆使用持续 > 85% 或 GC 频繁（`jvm_gc_pause_seconds_sum` 在保持阶段每分钟 > 1 秒） | 堆 2 → 2.5 GiB，app `mem_limit` 同步加 512 MiB，从 mysql 或 rule-runner 中收回 | `JAVA_TOOL_OPTIONS -Xmx`、`mem_limit` |
| MySQL CPU 高、读题慢 | 检查慢查询（`SET GLOBAL slow_query_log=ON`），缓冲池 1536M → 2G（mysql `mem_limit` 同步增加） | prod compose 的 mysql `command` |
| app CPU 打满（宿主机 CPU 空闲 < 10%） | 先用 JFR 定位热点（`loadtest/README.md` 的 profiling 段落），不要先加资源 | — |
| 导入拖慢提交 | 导入并发 `QF_IMPORT_JOBS_CONCURRENCY` / `QF_IMPORT_EXTENSION_CONCURRENCY` 调低 | prod compose 的 app 环境（默认见 `docker-compose.yml`） |

所有调整后更新 `docker-compose.prod.yml` 头部的内存表、`01-deploy.md` 第 5 节的生产值，以及 `deploy/monitoring/alerts.yml` 的阈值（按复测基线的 3～5 倍设告警线）。
