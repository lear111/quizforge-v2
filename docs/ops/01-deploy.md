# 生产部署与运维

目标环境：一台 4 核 8 GiB 的 Linux 服务器（Docker Engine 25+、Docker Compose 2.24.4+），对外只有 Nginx 的 80 / 443。
本文的命令由作者在服务器上执行；仓库里的脚本只在本机演练过（演练记录见 [03-restore-drill.md](03-restore-drill.md)）。
从旧平台切换的步骤与回退见 [02-cutover.md](02-cutover.md)，容量复测见 `loadtest/CAPACITY.md`。

文中代码、注释与告警会引用本文的节号（第 3～7 节），改动节号时一并修改。

## 1. 文件与目录

| 位置 | 内容 |
| --- | --- |
| `/opt/quizforge`（仓库检出，可自选） | 代码、`deploy/` 下的 compose 与脚本。下文命令都在这里执行 |
| `/etc/quizforge/prod.env` | 全部密钥与生产配置，`chmod 600`、属主 root。模板 `deploy/prod.env.example` |
| `/etc/quizforge/tls/` | 证书链与私钥（第 3 节）。只读挂载到 Nginx |
| `/var/www/acme` | Let's Encrypt HTTP-01 验证目录（只在使用 certbot 时需要） |
| `/srv/quizforge/downloads` | 客户端安装包（`tools/downloads/README.md`） |
| `/var/backups/quizforge` | 每天的备份（第 6 节），还要复制到服务器之外 |
| Docker 数据卷 `quizforge_mysql`、`quizforge_blobs` | 数据库与内容寻址文件目录（题库资源、原始题库文件、答题图片）。**不要 `down -v`** |

所有 compose 命令都带同一组参数，先在 shell 里定义（也写进 root 的 `~/.bashrc`）：

```bash
export QF_COMPOSE_PROJECT=quizforge
export QF_COMPOSE_ARGS="--env-file /etc/quizforge/prod.env -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml"
alias qf='docker compose -p "$QF_COMPOSE_PROJECT" $QF_COMPOSE_ARGS'
```

使用监控时在 `QF_COMPOSE_ARGS` 末尾再加 `-f deploy/docker-compose.monitoring.yml`（第 7 节）。

## 2. 首次部署

1. 安装 Docker（官方仓库的 `docker-ce` 与 `docker-compose-plugin`），确认 `docker compose version` ≥ 2.24.4。
2. 防火墙只放行 22、80、443。Prometheus / Alertmanager 只绑定 127.0.0.1，用 SSH 隧道查看。
3. 检出代码到 `/opt/quizforge`，切到要部署的提交（`git checkout <tag 或提交>`，记下提交号，回退时要用）。
4. 准备 `prod.env`：

   ```bash
   install -d -m 700 /etc/quizforge
   install -m 600 deploy/prod.env.example /etc/quizforge/prod.env
   openssl rand -hex 32      # 数据库密码、runner 令牌、两个 pepper 各生成一个
   openssl rand -base64 32   # QF_AI_MASTER_KEY
   ```

   按第 5 节逐项填写。`QF_AI_MASTER_KEY` 另外抄一份放到密码管理器：它和备份分开保存，丢失后兑换码与上游 Key 无法解密。
5. 证书：按第 3 节准备，放进 `/etc/quizforge/tls/`。
6. 安装包放进 `QF_DOWNLOADS_DIR`（目录可以先为空，下载页显示“暂无”）。
7. 构建并启动：

   ```bash
   qf config --quiet                 # 缺少必需变量时这里就报错
   qf up -d --build --wait           # 首次启动应用需要 1～3 分钟（Flyway 建表）
   qf ps                             # 四个服务都是 healthy
   curl -fsS https://quizforge.treenewbee.cloud/api/health
   ```

   应用拒绝启动时看 `qf logs app | grep 生产配置不完整`：每一项写明变量名与原因（第 5 节）。
8. 设置管理员（账号必须已注册或已迁移，见 `02-cutover.md`）：

   ```bash
   qf run --rm --no-deps -e JAVA_TOOL_OPTIONS=-Xmx512m app maintenance promote <邮箱或手机号>
   ```

9. 配置每天的备份与最低检查（第 6、7 节），立刻手动执行一次备份并校验。
10. 逐条完成第 9 节的检查清单。

## 3. HTTPS 与证书

Nginx 使用 `deploy/nginx/prod.conf.template`（启动时用环境变量生成配置）：

- 80 端口只提供 `/.well-known/acme-challenge/`，其余 301 跳转到 `https://<QF_SERVER_NAME>`。
- 443 只接受 TLS 1.2 / 1.3；Host 不是 `QF_SERVER_NAME` 的请求直接断开（不暴露默认站点），未知 SNI 拒绝握手。
- HSTS `max-age=31536000`，**不带** `includeSubDomains`（子域名可能还有其他服务）；Spring 自带的 HSTS 已被隐藏，避免两份。
- 证书文件：`QF_TLS_DIR` 是宿主机目录，`QF_TLS_CERT` / `QF_TLS_KEY` 是目录内的相对路径（证书文件必须是**完整链**）。

### 3.1 Let's Encrypt（certbot，推荐）

证书由宿主机上的 certbot 申请与续期，验证文件写到 `QF_ACME_DIR`，由 Nginx 的 80 端口提供。

首次申请时 Nginx 还没有证书无法启动，先用 standalone 模式（此时 80 端口必须空闲）：

```bash
apt install certbot
certbot certonly --standalone -d quizforge.treenewbee.cloud --agree-tos -m <邮箱> --no-eff-email
```

`prod.env`：

```
QF_TLS_DIR=/etc/letsencrypt
QF_TLS_CERT=live/quizforge.treenewbee.cloud/fullchain.pem
QF_TLS_KEY=live/quizforge.treenewbee.cloud/privkey.pem
QF_ACME_DIR=/var/www/acme
```

> `live/` 下是指向 `archive/` 的符号链接，所以挂载整个 `/etc/letsencrypt`，不要只挂 `live/`。

站点运行后改为 webroot 续期，并在续期后让 Nginx 重新加载：

```bash
install -d /var/www/acme
certbot certonly --webroot -w /var/www/acme -d quizforge.treenewbee.cloud --force-renewal \
  --deploy-hook 'cd /opt/quizforge && docker compose -p quizforge --env-file /etc/quizforge/prod.env -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml exec -T nginx nginx -s reload'
certbot renew --dry-run
```

certbot 的 systemd timer 每天检查两次，到期前 30 天续期。第 7 节的最低检查在证书剩余 14 天时报警。

### 3.2 自备证书

把完整证书链（服务器证书在前，中间证书在后）与私钥放进 `/etc/quizforge/tls/`：

```bash
install -d -m 750 /etc/quizforge/tls
install -m 644 fullchain.pem /etc/quizforge/tls/fullchain.pem
install -m 600 privkey.pem  /etc/quizforge/tls/privkey.pem
openssl x509 -in /etc/quizforge/tls/fullchain.pem -noout -subject -enddate   # 域名与到期日
```

`prod.env`：`QF_TLS_DIR=/etc/quizforge/tls`、`QF_TLS_CERT=fullchain.pem`、`QF_TLS_KEY=privkey.pem`，`QF_ACME_DIR` 留空。
Nginx 容器以 root 读取私钥后降权，`600` 即可。更换证书后执行 `qf exec nginx nginx -s reload`，并在日历里记下到期日。

### 3.3 部署后检查

```bash
curl -sI http://quizforge.treenewbee.cloud/ | head -n 3          # 301 → https
curl -sI https://quizforge.treenewbee.cloud/ | grep -iE 'strict-transport|content-security'
echo | openssl s_client -connect quizforge.treenewbee.cloud:443 -servername quizforge.treenewbee.cloud 2>/dev/null | openssl x509 -noout -issuer -enddate
```

旧客户端（Windows / Android）写死这个 HTTPS 域名，自签或过期证书会让它们完全连不上。

## 4. 安全响应头与 CSP

所有响应（包括错误页、静态资源、`/_qf_blobs/` 内部跳转）都带：`Content-Security-Policy`、`Strict-Transport-Security`（仅 HTTPS）、
`X-Content-Type-Options: nosniff`、`Referrer-Policy: strict-origin-when-cross-origin`、`Permissions-Policy`（关闭摄像头、麦克风、定位等）。
定义在 `deploy/nginx/qf/maps.conf` 与 `security-headers.conf`，开发与生产共用，所以 CI 的浏览器测试就在 CSP 下运行。

先列出页面实际需要的来源，再定策略：

| 页面 | 需要的来源 | 原因 |
| --- | --- | --- |
| 所有页面 | `script-src 'self'` | 前端只用同源脚本，没有 `eval` / 内联事件 |
| 登录、注册、找回密码 | `script-src` / `frame-src https://challenges.cloudflare.com` | Turnstile 人机验证 |
| 所有页面 | `style-src 'self' 'unsafe-inline'` | 富文本与组件写 `style` 属性 |
| 所有页面 | `img-src 'self' data: blob:` | 题目图片（同源 `/api/resources/...`）、白板导出、头像预览 |
| 练习空间 `/practice/` | `script-src 'self' 'unsafe-inline' blob:`、`frame-src 'self'` | 拓展页面用 `srcdoc` iframe：iframe 继承并**同时**受宿主 CSP 约束，宿主必须允许内联脚本 |
| 练习空间 | `worker-src 'self' blob:` | 浏览器判分 Worker |
| 练习空间 | `media-src 'self' https: blob:`、`connect-src 'self' https: blob: data:` | 网络音频（题目引用的外部 HTTPS 音频）、资源读取 |
| `/connect/authorize` | Nginx 不加 CSP | 客户端授权页由 Java 返回自己的 CSP |
| `/api/`、`/_qf_blobs/`、`/_qf_sdk/` | `default-src 'none'` | 数据与文件不需要执行任何东西 |

拓展 iframe 与判分 Worker 的真正限制不在宿主 CSP，而在它们自己的策略里，保持不变：
iframe 的 `<meta>` CSP（`frontend/practice/` 的 frame 宿主，禁止网络与同源访问）与 `sandbox` 属性；Worker 内的 `browser-rules.js` 禁用网络与计时器。
宿主页面的 `'unsafe-inline'` 只在 `/practice/` 下生效；其他页面仍然禁止内联脚本。

改动前端引入新来源时：先改 `maps.conf`，再运行 `frontend/test/browser/security-headers.spec.mjs`（收集各页面的 CSP 违规，任何一条都失败）。

## 5. 生产配置与启动检查

两层检查：

1. **Compose**：`deploy/docker-compose.prod.yml` 中 `${VAR:?}` 的变量缺失时，`qf config` / `qf up` 直接报错，写明变量名。
2. **应用**：`ProductionConfigCheck` 在其他 Bean 创建之前运行。验证码模式不是 `fake`、或站点来源不是本机 / `.localhost` / `.test` 时视为生产，
   下表任何一项缺失、过短或仍是开发默认值 / 示例值（`change-me`、`your-`、`<...>`、`example.com` 等）就拒绝启动。
   一次列出全部问题，每项一行 `生产配置不完整：<变量名>：<原因>`，**不打印值**。维护命令（`maintenance promote` 等）同样检查。
   这一检查合并了 #123 的开发默认凭据检查（原 `DeploymentSecrets`：生产不得使用仓库默认的 runner 令牌与数据库密码）。

| 变量 | 要求 | 说明 |
| --- | --- | --- |
| `QF_VERIFICATION_MODE` | `provider` | prod compose 中写死；`fake` 会在日志里打印验证码 |
| `QF_PUBLIC_ORIGIN` | `https://` 加域名，不带路径 | 同时用于 CSRF 来源检查与客户端回调；`https://quizforge.treenewbee.cloud` |
| `QF_SERVER_NAME` | 与上面的域名相同 | Compose 检查 |
| `QF_DB_PASSWORD` | ≥ 16 字符，不是 `quizforge` | 应用账号 |
| `QF_DB_ROOT_PASSWORD` | 必填 | Compose 检查；备份与恢复使用 |
| `QF_RULE_RUNNER_TOKEN` | ≥ 32 字符，不是开发令牌 | app、rule-runner、Prometheus 共用 |
| `QF_EMAIL_CODE_PEPPER`、`QF_SMS_CODE_PEPPER` | ≥ 32 字符 | 验证码摘要 |
| `QF_AI_MASTER_KEY` | 32 字节 Base64 | 兑换码与上游 Key 的加密；与备份分开保存 |
| `QF_TENCENT_SES_ENABLED` + `SECRET_ID` / `SECRET_KEY` / `SES_FROM` / `SES_TEMPLATE_ID` | `true` + 全部填写 | 邮件验证码；从旧平台配置抄写 |
| `QF_ALIYUN_SMS_ENABLED` + `ACCESS_KEY_ID` / `ACCESS_KEY_SECRET` / `SIGN_NAME` / `TEMPLATE_CODE` | `true` + 全部填写 | 短信验证码 |
| `QF_TURNSTILE_SITE_KEY`、`QF_TURNSTILE_SECRET_KEY` | 必填 | Turnstile；站点密钥的域名列表要包含正式域名 |
| `QF_TLS_DIR`、`QF_TLS_CERT`、`QF_TLS_KEY` | 必填 | Compose 检查（第 3 节） |

prod compose 显式写出、不依赖默认值的生产值：`QF_VERIFICATION_MODE=provider`、`QF_GRADING_MODE=browser`（ADR-011）、
`QF_RULE_WORKER_LIMIT=4`、`QF_DB_POOL_SIZE=20`、JVM 堆 1～2 GiB。启动检查无法判断的事（密钥是否真的有效、模板是否审核通过）要在第 9 节逐项实测。

## 6. 备份与恢复

### 6.1 备份内容

`deploy/backup/backup.sh` 每次在 `QF_BACKUP_DIR/<日期-时间>/` 下生成：

| 文件 | 内容 |
| --- | --- |
| `mysql.sql.gz` | `mysqldump --single-transaction --routines --triggers --events`：InnoDB 一致性快照，不锁表，备份期间可以照常使用 |
| `blobs.tar` | 内容寻址文件目录（题库资源、**原始题库文件**、答题图片、广场下载的归档） |
| `MANIFEST` | 时间、项目名、Flyway 版本、账号数、文件数 |
| `SHA256SUMS` | 上面各文件的 SHA-256 |

先导出数据库、再打包文件：文件按 SHA-256 只增不改，所以数据库里引用的文件一定已在包内。
备份完成后自动校验（SHA-256 与 `SHA256SUMS` 一致、压缩包完整、SQL 有结束标记、文件数与清单一致、抽查 20 个文件的内容与文件名一致），失败时整份丢弃并返回非 0；
成功后只保留最近 `QF_BACKUP_KEEP` 份（默认 7）。所有数据通过标准输入输出传给容器，密码不出现在命令行和日志里。

### 6.2 定时备份

```bash
install -d -m 700 /var/backups/quizforge
cat > /etc/cron.d/quizforge <<'EOF'
SHELL=/bin/bash
MAILTO=root
QF_COMPOSE_PROJECT=quizforge
QF_COMPOSE_ARGS=--env-file /etc/quizforge/prod.env -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml
QF_BACKUP_DIR=/var/backups/quizforge
QF_BACKUP_KEEP=7
QF_CHECK_URL=https://quizforge.treenewbee.cloud
# 每天 04:10 备份（UTC+8 凌晨使用最少）
10 4 * * * root cd /opt/quizforge && deploy/backup/backup.sh >> /var/log/quizforge-backup.log 2>&1
# 每 10 分钟最低检查（第 7.3 节），异常时输出会发到 MAILTO
*/10 * * * * root cd /opt/quizforge && deploy/ops/check.sh > /dev/null || deploy/ops/check.sh
EOF
```

服务器时区以 `timedatectl` 为准；数据库内部时间是 UTC，与 cron 无关。

### 6.3 复制到服务器之外

只放在本机的备份防不住磁盘损坏、误删和服务器失陷。至少选一种，每天备份后执行：

- 对象存储：`rclone copy /var/backups/quizforge remote:quizforge-backup --max-age 48h`（存储桶开启版本保留，服务器上的凭据只给“写入”权限，不给删除）。
- 另一台机器：`rsync -a /var/backups/quizforge/ backup@<另一台机器>:quizforge/`，由对方机器的 cron 拉取更好（服务器上不保存对方的凭据）。

`prod.env` 与证书私钥不在备份里：另外加密保存一份（例如 `gpg -c /etc/quizforge/prod.env` 后放到密码管理器）。
**没有 `QF_AI_MASTER_KEY` 的备份恢复后，兑换码与上游 Key 无法解密。**

### 6.4 校验与恢复

```bash
deploy/backup/verify.sh /var/backups/quizforge/<目录>          # 同备份后的自动校验
deploy/backup/verify.sh /var/backups/quizforge/<目录> --full   # 检查全部文件的内容与文件名
deploy/backup/restore.sh /var/backups/quizforge/<目录>         # 不加 --yes：只校验并说明要做什么
deploy/backup/restore.sh /var/backups/quizforge/<目录> --yes   # 覆盖当前数据库与文件目录
```

`restore.sh --yes` 的步骤：校验 → 停止 Nginx 与应用（停止写入）→ 确保 MySQL 运行 → 删除并重建数据库、导入 → 恢复文件目录 →
启动全部服务（Flyway 只执行备份之后新增的迁移）。数据卷不存在（新服务器）时会自动创建。演练用时与结果见 `03-restore-drill.md`。

恢复后检查：登录一个已知账号；打开一个题库并读题；作答与历史记录还在；广场下载一个题库包与备份前的 SHA-256 一致；后台 AI 余额对账（第 7.3 节）。

在新服务器上恢复：按第 2 节 1～6 步准备（**使用原来的 `prod.env`**），把备份目录复制过来，执行 `restore.sh --yes`，再切换 DNS。

## 7. 监控与告警

### 7.1 健康检查

| 检查 | 位置 | 失败时 |
| --- | --- | --- |
| `GET /api/health` | 应用（包含数据库连通） | Compose 标记 unhealthy；外部检查报警 |
| `/nginx-health` | Nginx 容器内 127.0.0.1:8081 | Compose healthcheck |
| MySQL `mysqladmin ping`、rule-runner `/v1/health` | 已有 compose healthcheck | 应用相关功能失败 |

所有服务 `restart: unless-stopped`；应用 OOM 时直接退出（`ExitOnOutOfMemoryError`）由 Docker 重启。日志 json-file 每个容器 20 MiB × 5 份。

### 7.2 Prometheus（可选，约 400 MiB）

```bash
cp deploy/monitoring/alertmanager.yml.example deploy/monitoring/alertmanager.yml   # 填写收件人与 SMTP
echo 'QF_MYSQL_EXPORTER_PASSWORD=<openssl rand -hex 24>' >> /etc/quizforge/prod.env
QF_MYSQL_EXPORTER_PASSWORD=<同上> deploy/monitoring/create-exporter-user.sh
export QF_COMPOSE_ARGS="$QF_COMPOSE_ARGS -f deploy/docker-compose.monitoring.yml"
qf up -d
ssh -L 9090:127.0.0.1:9090 -L 9093:127.0.0.1:9093 <服务器>        # 在自己电脑上查看
```

抓取：应用 `/actuator/prometheus`（管理端口只在内部网络）、rule-runner 指标（带内部令牌）、mysqld_exporter。
本 PR 新增的应用指标：`qf_import_queue_length{status}`、`qf_import_jobs_failed_recent`、`qf_ai_tasks_failed_recent`、`qf_ai_tasks_queue_length`
（每 30 秒查询一次数据库并缓存，数据库不可用时为 NaN），以及内容寻址文件目录所在卷的 `disk_free_bytes` / `disk_total_bytes`。

告警规则（`deploy/monitoring/alerts.yml`，阈值是起始值，复测后调整）与处理方法：

| 告警 | 含义 | 先做什么 |
| --- | --- | --- |
| `AppDown` / `RuleRunnerDown` / `MySqlDown` | 服务不可用 | `qf ps`、`qf logs --tail 200 <服务>`；内存不足看 `dmesg \| grep -i oom` |
| `High5xxRate` | 5xx 超过 1% | 应用日志中的异常；是否刚升级（第 8 节回退） |
| `SubmitSlow` | 保存 / 提交 P95 > 1 秒 | 数据库连接池、慢查询、是否在大量导入 |
| `DbPoolExhausted` | 有请求等待连接 | 同上；长期出现时按 `loadtest/CAPACITY.md` 调整连接池 |
| `JvmHeapHigh` | 堆使用 > 90% 持续 15 分钟 | 观察是否回落；持续升高时重启应用并保存 `jcmd <pid> GC.heap_dump` |
| `RuleRunnerRejecting` / `RuleRunnerTimeouts` | 规则并发满 / 超时 | 是否有题库规则死循环（导入记录）；runner 进程数 |
| `ImportQueueBacklog` / `ImportJobsFailing` | 导入积压 / 失败 | 后台导入任务列表；rule-runner 状态；磁盘 |
| `AiTasksFailing` | AI 评分失败多 | 后台“AI 服务”的 Key 余额与上游状态 |
| `DiskSpaceLow` / `DiskSpaceCritical` | 数据卷剩余 < 15% / 5% | `docker system df`；清理旧镜像 `docker image prune`；备份目录；扩容 |

### 7.3 不部署 Prometheus 时的最低检查

`deploy/ops/check.sh`（第 6.2 节的 cron 每 10 分钟运行）检查：外部 HTTPS 健康接口与数据库、四个容器都是 healthy、
Docker 数据目录与备份目录剩余空间 ≥ 15%、26 小时内有成功的备份、证书剩余 ≥ 14 天、最近 2 小时没有 AI 余额对账告警。
正常时输出“正常”并返回 0；否则逐条输出异常并返回 1，由 cron 的 `MAILTO` 发出（需要服务器能发邮件，或改成调用自己的通知服务）。

另外每周手动看一次：

```bash
qf run --rm --no-deps -e JAVA_TOOL_OPTIONS=-Xmx512m app maintenance ai-reconcile   # 0 = 余额与流水一致，3 = 不一致（输出明细）
qf logs app --since 168h | grep -cE ' ERROR '
docker system df
```

外部可用性建议再加一个免费的外部拨测（例如 UptimeRobot 访问 `/api/health`），服务器整机宕机时 cron 也不会运行。

## 8. 日常升级与回退

### 8.1 升级

```bash
cd /opt/quizforge
git fetch && git log --oneline HEAD..origin/main      # 看要上线的提交，特别是 backend/src/main/resources/db/migration 下新增的迁移
deploy/backup/backup.sh                               # 升级前先备份（记下目录名）
git checkout <新的 tag 或提交>
qf build app rule-runner                              # 先构建，服务照常运行
qf up -d --wait                                       # 重新创建有变化的容器；应用启动时 Flyway 执行新迁移
curl -fsS https://quizforge.treenewbee.cloud/api/health
qf logs app --since 10m | grep -E 'Migrating schema|Successfully applied|ERROR'
```

应用重启期间（约 1～2 分钟）网站返回 502，选使用最少的时间。浏览器缓存的前端会在下次加载时更新。

### 8.2 回退

Flyway 迁移只向前执行，不自动回退。按升级是否包含新迁移分两种：

- **没有新迁移**：`git checkout <上一个提交>` → `qf up -d --build --wait`。数据不受影响。
- **有新迁移**：
  1. 迁移是“只新增”（新表、新列、新索引）且旧代码能忽略它们时，可以只回退代码（同上）。旧代码启动时 Flyway 发现数据库版本更高会报错，
     所以回退期间给应用设置 `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS=*:future`（加到 prod.env 后 `qf up -d`），重新升级后删除。
  2. 迁移修改或删除了旧代码依赖的结构，或不确定时：用升级前的备份恢复（`restore.sh <升级前的目录> --yes`），再回退代码。
     **升级之后写入的数据会丢失**，所以出问题要尽早决定；决定前先再做一次备份留存证据。

每个包含迁移的 PR 应在描述中写明属于哪一种。不得修改已应用的迁移（`CLAUDE.md`）。

### 8.3 其他日常操作

| 操作 | 命令 |
| --- | --- |
| 设置 / 取消管理员 | `qf run --rm --no-deps -e JAVA_TOOL_OPTIONS=-Xmx512m app maintenance promote <账号>`（取消见 `maintenance` 帮助） |
| 修改 prod.env 后生效 | `qf up -d`（只重建配置变化的容器） |
| 更换安装包 | 放进 `QF_DOWNLOADS_DIR`，无需重启（`tools/downloads/README.md`） |
| 清理旧镜像 | `docker image prune -f`（每次升级后） |
| 查看最近的错误 | `qf logs app --since 1h \| grep -E ' (ERROR\|WARN) '` |

## 9. 上线前检查清单

汇总 L4～L8 各 PR 记录的上线注意事项。每次首次部署或换服务器都逐条勾选。

### 9.1 服务器与配置

- [ ] Docker Compose ≥ 2.24.4；防火墙只开放 22 / 80 / 443
- [ ] `/etc/quizforge/prod.env` 按第 5 节填写，权限 600；`qf config --quiet` 无报错
- [ ] 应用启动日志没有“生产配置不完整”；`QF_VERIFICATION_MODE=provider`（provider 模式必须显式设置，L4）
- [ ] `QF_AI_MASTER_KEY` 与 `prod.env` 已加密保存在服务器之外
- [ ] 内存：`docker stats --no-stream` 中各容器低于第 1 节的上限，宿主机 `free -m` 有余量
- [ ] `loadtest/CAPACITY.md` 的复测已在这台服务器上完成并记录结果

### 9.2 域名与证书（遗留契约 C-05）

- [ ] V2 接管 `quizforge.treenewbee.cloud`，证书有效、链完整（第 3.3 节）；已发布客户端写死这个 HTTPS 域名
- [ ] HTTP 跳转 HTTPS；HSTS 与 CSP 响应头存在
- [ ] 证书续期已配置（certbot timer 与 deploy hook，或自备证书的到期提醒）

### 9.3 外部服务实测（启动检查只能判断“已填写”）

- [ ] 邮件：用新邮箱注册，收到验证码（腾讯云 SES 模板已审核、发件域名已验证）
- [ ] 短信：用新手机号注册与找回密码，收到验证码（阿里云签名与模板）
- [ ] Turnstile：登录、注册页面显示并通过人机验证（站点密钥的域名列表包含正式域名）
- [ ] AI：后台“AI 服务”添加上游 Key；一次 AI 辅导与一次 AI 评分成功，余额按单价扣减（L5）
- [ ] 兑换码：后台生成一个兑换码并兑换成功

### 9.4 数据与账号

- [ ] 账号迁移按 `02-cutover.md` 完成并通过 verify（只迁移账号与密码，L7；迁移要求目标库没有账号，所以先迁移再设置管理员）
- [ ] 迁移用户的默认 AI 额度已发放（`02-cutover.md`）
- [ ] 管理员已设置；用迁移账号原密码登录成功

### 9.5 客户端（遗留契约 C-05）

- [ ] 下载页提供支持题库格式 4 的新版 Windows 客户端（旧 Windows 用户需要升级才能导入题库）
- [ ] 旧 Windows 客户端真机验收：重新授权、余额、兑换、AI 评分、退出（契约测试的期望值来自旧源码，没有和线上响应对比过）
- [ ] 新 Windows 客户端：授权、下载题库并导入（固定版本导入）
- [ ] Android 客户端不更新（作者决定），确认授权与 AI 连接仍可用

### 9.6 备份与监控

- [ ] 手动备份一次并 `verify.sh --full` 通过；cron 已配置
- [ ] 备份复制到服务器之外，并试过从那里取回
- [ ] 在另一个项目名或另一台机器上做过一次恢复（`03-restore-drill.md` 的步骤）
- [ ] `deploy/ops/check.sh` 手动运行输出“正常”；cron 异常邮件能收到（临时把阈值调高试一次）
- [ ] （可选）Prometheus 所有 target 为 UP，Alertmanager 能发出测试告警
- [ ] 外部拨测已配置

### 9.7 已知的单实例限制（上线时只运行一个应用实例）

下面这些是进程内实现，**不要**横向扩成多个应用实例；应用重启后计数清零（可以接受）：

- 匿名认证接口的 IP 限频（每 IP 每分钟 12 次）、练习与导入的频率限制
- 兑换失败锁定（1 小时 10 次）、AI 每用户 / 全站并发
- 在线活跃统计（心跳在内存中计数，每分钟写入）

限频按客户端 IP 计算：Nginx 传递真实 IP（`X-Forwarded-For`），前面如果再加 CDN 或反向代理，要先确认真实 IP 仍能传到应用，
否则所有用户共用一个 IP 的限额。
