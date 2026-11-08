# 从旧平台切换到 V2 与回退方案

依据：需求 FR-09 v1.12、`docs/design/09-migration.md`（以开头的“2026-11-01 范围变更”为准：**只迁移账号与密码**）、
`tools/migration/README.md`、`01-deploy.md`。所有服务器上的操作由作者执行；AI 编码助手只准备命令与文档，不连接任何服务器。

## 0. 前提与约定

- 旧平台（`quizforge_hub`）运行在旧服务器上，域名 `quizforge.treenewbee.cloud` 当前指向它；V2 部署在**另一台** 4 核 8G 服务器（下称“新服务器”）。
  如果 V2 与旧平台要放在同一台机器上，第 3 节的“部署 V2”要改为先停旧平台再启动 V2（80 / 443 只能给一个），停机时间相应变长——这一点需要作者确认（见文末“待确认”）。
- 迁移工具要求 V2 的账号与 AI 表**全部为空**（包括 `auth_session`：在 V2 上登录过一次就不能再迁移），并且 V2 已经建好表（Flyway ≥ V16）。
  所以顺序是：**启动 V2 但不开放** → 迁移 → 设置管理员 → 切域名 → 开放。迁移前不要在 V2 上注册或登录任何账号。
- 迁移工具 jar 在作者自己的电脑上构建（需要 Docker 跑测试）：`mvn -B -ntp -f tools/migration/pom.xml verify`，得到 `tools/migration/target/quizforge-migration.jar`，复制到新服务器的 `/opt/quizforge-migration/`。
- 下文 `qf` 是 `01-deploy.md` 第 1 节定义的 compose 别名；时间以开始停机的时刻 **T** 为准。

迁移工具在新服务器上用一个临时 Java 容器运行，接入 V2 的内部网络直接连 MySQL（MySQL 不对外开放端口）：

```bash
export QF_MIGRATION_TARGET_PASSWORD="$(sed -n 's/^QF_DB_PASSWORD=//p' /etc/quizforge/prod.env)"
alias qfmig='docker run --rm -i --network quizforge_internal \
  --add-host quizforge.treenewbee.cloud:host-gateway \
  -e QF_MIGRATION_TARGET_PASSWORD -v /opt/quizforge-migration:/work -w /work \
  eclipse-temurin:21-jre java -jar quizforge-migration.jar'
TARGET=jdbc:mysql://mysql:3306/quizforge
```

`--add-host` 让容器里的 `https://quizforge.treenewbee.cloud` 指向本机 Nginx，所以在切换 DNS 之前就能用真实登录接口抽查原密码。

### 0.1 新旧平台在同一台服务器上（作者 2026-11-07：可能采用）

如果 V2 与旧平台部署在同一台 4 核 8G 机器上，下文按以下差异执行：

| 项 | 两台服务器（下文默认） | 同一台服务器 |
| --- | --- | --- |
| 证书 | 切换前在新服务器上取得（第 1 节三选一） | **直接复用**旧平台正在使用的证书与私钥，按自备证书配置；不需要 DNS-01 |
| 第 1 节 T−1 天“TTL 调到 300 秒” | 需要 | **不需要**：域名不变、IP 不变，不切 DNS |
| 第 2 节第 4 步“启动 V2（不开放）” | 与旧平台并行启动 | 旧平台已在第 1 步停止并释放 80 / 443 后再启动 V2；启动期间旧 Nginx 的维护页已停，网站不可访问（停机的一部分）。抽查原密码时用 `--resolve quizforge.treenewbee.cloud:443:127.0.0.1` |
| 第 2 节第 12 步“切换域名” | 改 A 记录 | **不需要**：V2 的 Nginx 启动后即接管 |
| 内存 | 各自独立 | 4 核 8G 不足以同时运行两套：旧平台在切换后**保持停止**（不删除），回退时再启动 |
| 停机时长 | 约 1～1.5 小时 | 增加“停旧平台 + 启动 V2 + 冒烟”的时间，以 T−3 天演练为准，预计 1.5～2 小时 |
| 回退（第 3.2 节） | DNS 改回旧服务器 | `qf down`（不加 `-v`，保留 V2 数据卷）→ 确认旧库 SHA-256 未变 → 启动旧平台与其 Nginx |
| 旧数据 | 留在旧服务器 | 旧平台的程序与 `hub.sqlite` 留在原目录（至少 30 天，FR-09），切换前另做一份副本 |

## 1. 提前准备

| 时间 | 执行者 | 事项 | 预期结果 | 不满足时 |
| --- | --- | --- | --- | --- |
| T−7 天 | 作者 | 发停机公告（站内、群、客户端公告）：停机时间与预计时长；**练习进度与历史不保留**、`.qbank` 需重新导出、需要重新登录、客户端需要重新授权、旧 Windows 客户端需升级到新版才能导入题库（FR-09、遗留契约 C-05） | 用户收到公告 | — |
| T−7 天 | 作者 | 新服务器按 `01-deploy.md` 第 2 节 1～6 步准备好（`prod.env`、证书、安装包），**不启动**正式项目；完成 `loadtest/CAPACITY.md` 的容量复测 | 复测达标 | 按 CAPACITY.md 第 5 节调整后再测；不达标不切换 |
| T−3 天 | 作者 + 本地 Claude | 取一份最新的旧库副本（方法同第 2 节第 3 步），在本机按 `09-migration.md` 第 5.4 节完整演练 inspect → dry-run → migrate → verify，记录用户数与耗时 | ERROR 为 0，WARN 已签注 | 修复工具或由作者决定数据问题，重新演练 |
| T−3 天 | 作者 | 准备已知密码文件 `known-passwords.json`（至少一个邮箱账号、一个只有手机号的账号、一个被封禁的账号，`tools/migration/README.md`），放在 `/opt/quizforge-migration/`，权限 600 | — | 没有只有手机号的账号时报 `LOGIN_SAMPLE_INCOMPLETE`，需要找一个或临时建一个 |
| T−1 天 | 作者 | 把域名 A 记录的 TTL 调到 300 秒 | `dig +noall +answer quizforge.treenewbee.cloud` 显示 300 | 来不及时切换后的生效时间按原 TTL 计算 |
| T−1 天 | 作者 | 新服务器的证书：DNS 还指向旧服务器，HTTP-01 验证无法通过，用下面任一方式在切换前拿到证书 | `openssl x509 -noout -subject -enddate` 正确 | 见下 |
| T−1 天 | 作者 | 用旧 Windows 客户端在旧平台上登录一次，确认还能用（切换后要用它做真机验收） | — | — |

证书（`01-deploy.md` 第 3 节）三选一：

1. **DNS-01**（推荐）：`certbot certonly --manual --preferred-challenges dns -d quizforge.treenewbee.cloud`，按提示添加 TXT 记录。之后续期仍用第 3.1 节的 webroot 方式（切换后 HTTP-01 可用）。
2. 复制旧平台当前使用的证书与私钥，按自备证书（第 3.2 节）配置；切换后再改用 certbot。
3. 切换后再申请（standalone）：切换 DNS 后有几分钟 HTTPS 不可用，旧客户端在这段时间会连接失败，不推荐。

## 2. 切换当天

预计总时长约 1～1.5 小时（迁移本身是秒级；时间主要用于快照、校验、冒烟检查）。按 T−3 天演练的耗时修正。

| 时间 | 执行者 | 步骤 | 命令 / 操作 | 预期结果 | 失败时 |
| --- | --- | --- | --- | --- | --- |
| T−0:30 | 作者 | 再次发公告：30 分钟后停机 | — | — | — |
| T+0:00 | 作者 | 1. 旧平台进入维护、停止写入 | 旧服务器 Nginx 换成维护页（所有请求返回 503 与说明），再停止旧 Hub 与练习服务进程 | 网页显示维护页；旧客户端提示连接失败；`ps` 中没有旧 Hub 进程 | 进程停不下来时不要继续：先确认没有写入 |
| T+0:05 | 作者 | 2. 复制旧库：SQLite 在线备份 | 旧服务器上：`sqlite3 <数据目录>/hub.sqlite ".backup '/root/hub-cutover.sqlite'"`，然后 `sqlite3 /root/hub-cutover.sqlite 'PRAGMA integrity_check;'`，`sha256sum /root/hub-cutover.sqlite` | 输出 `ok`；记下 SHA-256。`.backup` 生成的是一个完整的单文件，不带 `-wal` | `integrity_check` 不是 ok：停止，用旧服务器上最近的完好备份或修复后再来；回退（第 3 节）只需撤掉维护页 |
| T+0:08 | 作者 | 3. 留存与传输 | 原副本留在旧服务器（回退备份，至少保留 30 天，FR-09）；另复制一份到新服务器 `/opt/quizforge-migration/hub.sqlite`（`scp`），在新服务器上再算一次 SHA-256 | 两边 SHA-256 相同 | 重新复制 |
| T+0:12 | 作者 | 4. 启动 V2（不开放） | 新服务器：`qf up -d --build --wait`（DNS 还指向旧服务器，外部用户访问不到）；`curl -fsS --resolve quizforge.treenewbee.cloud:443:127.0.0.1 https://quizforge.treenewbee.cloud/api/health` | 四个服务 healthy；health 返回 `database: up`；日志没有“生产配置不完整” | 按日志修正 `prod.env`；本步不影响旧数据 |
| T+0:17 | 作者 | 5. inspect | `qfmig inspect --source hub.sqlite --report inspect-cutover.json` | 用户数与 T−3 天演练一致（只多了这三天的新用户），ERROR 为 0 | 有 ERROR：对照演练报告；无法当场解决就回退 |
| T+0:20 | 作者 | 6. dry-run | `qfmig migrate --source hub.sqlite --target-url $TARGET --dry-run --report dryrun-cutover.json` | 退出码 0，报告中迁移用户数正确，目标库仍为空 | 退出码 1：看报告的 ERROR；`TARGET_NOT_EMPTY` 表示有人在 V2 上注册或登录过——`qf down`，删除 V2 数据卷 `docker volume rm quizforge_mysql`，从第 4 步重来 |
| T+0:22 | 作者 | 7. migrate | `qfmig migrate --source hub.sqlite --target-url $TARGET --report migrate-cutover.json` | 退出码 0；同一事务内已为每个用户发放 1 元默认额度并核对 | 失败时事务已整体回滚，目标库仍为空：看报告，修正后重跑；不能当场解决就回退 |
| T+0:24 | 作者 | 8. verify（离线 + 真实登录） | `qfmig verify --source hub.sqlite --target-url $TARGET --known-passwords known-passwords.json --report verify-offline.json`，再加 `--login-url https://quizforge.treenewbee.cloud` 运行一次（`--report verify-login.json`） | 两次都退出码 0；样本账号 `login` / `blocked` 符合预期 | `VERIFY_MISMATCH` / `LOGIN_CHECK_FAILED`：不要开放，按报告排查；不能解决就回退。真实登录受每 IP 每分钟 12 次限频，样本多时分批 |
| T+0:28 | 作者 | 9. 发放默认额度（核对） | `qfmig grant-default-credit --target-url $TARGET --report credit-cutover.json` | 发放 **0 人**（migrate 已发放）；可重复执行 | 发放人数 > 0：说明有用户缺少默认额度，记录在案（已补发，无需回滚） |
| T+0:30 | 作者 | 10. 设置管理员 | `qf run --rm --no-deps -e JAVA_TOOL_OPTIONS=-Xmx512m app maintenance promote <作者的账号>` | 命令成功 | 账号不存在：确认用迁移过来的账号（邮箱或手机号） |
| T+0:32 | 作者 | 11. 备份一次 | `deploy/backup/backup.sh`（`01-deploy.md` 第 6 节） | 备份校验通过 | 先解决再继续（磁盘、权限） |
| T+0:35 | 作者 | 12. 切换域名 | DNS A 记录改为新服务器 IP；旧服务器 Nginx 保持维护页 | `dig @1.1.1.1 quizforge.treenewbee.cloud` 返回新 IP（TTL 300，几分钟内生效） | DNS 服务商故障：等待或改回 |
| T+0:40 | 作者 | 13. 冒烟检查（下表） | 在自己的电脑上，确认解析到新 IP 后逐项检查 | 全部通过 | 按下表“失败时”处理；严重问题按第 3 节回退 |
| T+1:10 | 作者 | 14. 开放 | 发公告：已恢复，附新客户端下载地址与常见问题；开始观察窗口 | — | — |

### 2.1 冒烟检查（第 13 步）

| 范围 | 检查 | 失败时 |
| --- | --- | --- |
| HTTPS | `01-deploy.md` 第 3.3 节三条命令：HTTP 跳转、HSTS / CSP、证书签发者与到期日 | 证书问题直接影响所有客户端：修正前不开放 |
| 网页：老用户 | 用迁移账号原密码登录（邮箱一个、手机号一个）；被封禁的账号不能登录 | 登录失败是回退条件（第 3 节） |
| 网页：新用户 | 用新邮箱注册（收到邮件验证码）、新手机号注册（收到短信）、找回密码；登录页 Turnstile 正常 | 外部服务配置问题：修正 `prod.env` 后 `qf up -d`；不阻止开放，但要在公告中说明 |
| 网页：练习 | 导入一个题库（上传 `.qbank`）→ 练习：作答、提交、看结果、再练一次、历史记录 | 严重问题按第 3 节判断 |
| 网页：广场与下载 | 广场下载一个题库包；下载页列出 Windows 安装包并能下载 | — |
| 网页：AI | 后台“AI 服务”添加上游 Key；一次 AI 辅导、一次 AI 评分；老用户余额为 1 元并按单价扣减 | 上游问题：暂停 AI（后台），不阻止开放 |
| 后台 | 管理员能进入后台；用户列表数量与迁移报告一致 | — |
| 旧 Windows 客户端（真机） | 重新授权、显示余额、兑换（后台生成一个测试兑换码）、AI 评分、退出（遗留契约 C-05：期望值来自旧源码，第一次和线上对比） | 记录差异；只影响旧客户端时不回退，提示用户升级新客户端，并修复后发布 |
| 新 Windows 客户端 | 授权、下载题库并导入（固定版本导入）、AI 评分 | 修复后发布；不影响网页用户 |
| Android 客户端 | 授权与 AI 连接（Android 不更新，作者决定） | 记录 |
| 运维 | `deploy/ops/check.sh` 输出“正常”；cron 已配置（`01-deploy.md` 第 6.2 节） | 补齐 |

## 3. 回退方案

### 3.1 什么时候回退

**观察窗口：开放后 24 小时内**（之后回退的代价——V2 中积累的新数据——通常已超过修复的代价，改为在 V2 上修复）。
窗口内出现下列任一情况、且 1 小时内无法修复时回退：

1. 迁移账号普遍不能用原密码登录，或账号数据错误（昵称、联系方式、封禁状态错乱）
2. 数据丢失或串号（用户看到别人的数据）、安全问题
3. 网站不可用或核心流程（登录、导入、练习、提交）对大多数用户失败
4. 证书或域名问题导致所有客户端无法连接

不回退的情况：个别题库导入失败、AI 上游故障、旧客户端的个别接口差异、性能偏慢但可用——在 V2 上修复。

### 3.2 怎么回退（约 15 分钟）

| 步骤 | 执行者 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| 1 | 作者 | 发公告：回退到旧平台，V2 期间的变更不保留（下面列出） | — |
| 2 | 作者 | V2 进入只读：新服务器 `qf stop app`（Nginx 返回 502），然后 `deploy/backup/backup.sh`——**保留 V2 的完整备份**，用于排查与补偿 | 备份校验通过 |
| 3 | 作者 | 导出需要补偿的记录（第 3.3 节的 SQL），保存到 `/root/v2-rollback/` | 得到几个 CSV |
| 4 | 作者 | DNS 改回旧服务器 IP | `dig` 返回旧 IP |
| 5 | 作者 | 旧服务器：确认 `hub.sqlite` 的 SHA-256 与切换时第 2 步记录的一致（停机期间没有被写入），启动旧 Hub 与练习服务，撤掉维护页 | 旧网页与旧客户端可以登录使用 |
| 6 | 作者 | 记录回退原因与 V2 备份位置；修复后重新安排切换（重新走第 1、2 节：V2 删除数据卷从空库开始，使用新的旧库快照） | — |

旧平台的数据在停机前一刻冻结、切换期间没有被修改，所以回退后旧平台与停机前完全一致。

### 3.3 V2 期间的新数据如何处理

V2 的数据**不回流**到旧平台（`09-migration.md` 第 5.5 节，ADR-008）。按类别：

| 数据 | 回退后 | 处理 |
| --- | --- | --- |
| 新注册的账号 | 旧平台没有 | 公告请这些用户在旧平台重新注册；名单（脱敏）见下面的 SQL |
| 修改的密码、昵称、联系方式 | 恢复为停机前 | 公告提醒 |
| 封禁 / 解封 | 恢复为停机前 | 管理员按导出的记录在旧平台手工补做 |
| AI 使用与扣费 | 旧平台余额为停机前的值（V2 的 1 元默认额度与消费不回流） | 不处理（金额小，用户不受损） |
| 兑换码 | V2 的兑换码从零开始，在旧平台上不存在 | 在 V2 兑换过的用户按导出记录，在旧平台由管理员补发等额余额 |
| 练习、导入的题库、历史、白板、广场发布 | 旧平台没有 | 公告请用户在旧平台重新导入；V2 备份保留，重新切换时不迁移这些数据（重新切换从空库开始） |
| 客户端授权 | 旧客户端需要在旧平台重新登录 | 公告提醒 |

导出补偿记录（新服务器上执行，表名以当前 V2 结构为准，导出前先 `SHOW TABLES` 确认）：

```bash
mkdir -p /root/v2-rollback && chmod 700 /root/v2-rollback
sql() { qf exec -T mysql sh -c 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -B "$MYSQL_DATABASE"' <<< "$1"; }
CUT='<开放时刻，UTC，例如 2026-11-20 02:00:00>'
sql "SELECT id, email, name, created_at FROM account_user WHERE created_at >= '$CUT';" > /root/v2-rollback/new-users.tsv
sql "SELECT b.user_id, u.email, b.reason, b.created_at FROM account_block b JOIN account_user u ON u.id = b.user_id WHERE b.created_at >= '$CUT';" > /root/v2-rollback/blocks.tsv
sql "SELECT l.user_id, l.delta, l.ref, l.created_at FROM ai_ledger l WHERE l.kind = 'redeem' AND l.created_at >= '$CUT';" > /root/v2-rollback/redeems.tsv
```

这些文件含个人信息：只放在服务器上的 `/root/v2-rollback/`（权限 700），补偿完成后删除。

## 4. 切换之后

- 观察窗口内每小时看一次 `deploy/ops/check.sh` 与 `qf logs app --since 1h | grep -c ' ERROR '`；第一天晚上确认自动备份成功。
- T+1 天：检查迁移用户的登录比例（后台用户列表的最近登录）、客户端连接情况。
- T+30 天：旧库快照只读保留至少 30 天（FR-09），之后归档；旧服务器可以下线。
- 第 9 节检查清单（`01-deploy.md`）中所有项勾完，在 `docs/plan/03-feature-inventory.md` 的上线前检查清单中更新状态。

## 已确认（作者 2026-11-07）

1. 新旧平台**可能在同一台服务器上**：按第 0.1 节执行。
2. 观察窗口按 24 小时；上线时间暂不确定（作者：先不着急部署）。
3. 回退时兑换码补偿：管理员在旧平台补发等额余额（第 3.3 节）。
4. 旧客户端固定版本导入的大小上限保持 **64 MiB**（与旧平台一致）；网页上传与发布仍为 128 MiB。
