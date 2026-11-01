# 旧平台账号迁移工具（L7）

依据 [详细设计 09](../../docs/design/09-migration.md)（以开头的“2026-11-01 范围变更”为准）与需求 FR-09 v1.12：
**只迁移账号与密码**——用户 ID、昵称、邮箱、手机号及其验证状态与 `verified_at`、未验证联系方式标记、密码哈希、
管理员标记、封禁状态。AI 余额、流水、每日用量与兑换码**都不迁移**；登录会话、设备令牌、验证码也不迁移。

Java 21 独立命令行程序（ADR-008：不包含在线上应用中），以只读方式打开旧 Hub 数据库的**副本**（SQLite），
写入 V2 的 MySQL。依赖版本与 `backend` 一致。

```powershell
mvn -B -ntp -f tools/migration/pom.xml verify            # 构建与测试（需要 Docker）
$env:QF_MIGRATION_TARGET_PASSWORD = '…'                  # 目标库密码只从环境变量读取
java -jar tools/migration/target/quizforge-migration.jar inspect --source tools/migration/local/hub.sqlite --report tools/migration/local/inspect.json
```

## 命令

| 命令 | 作用 |
| --- | --- |
| `inspect --source 副本 [--report 文件]` | 只读检查：完整性与外键检查、表结构核对、逐用户转换，报告用户数、冲突与无法迁移的记录。不连接目标库 |
| `migrate --source 副本 --target-url JDBC [--target-user 用户] [--dry-run] [--report 文件]` | 迁移。`--dry-run` 完整执行并在同一事务内核对后回滚 |
| `verify --source 副本 --target-url JDBC [--known-passwords 文件] [--login-url V2地址] [--report 文件]` | 逐用户核对全部字段；用已知密码确认原密码能登录 |
| `grant-default-credit --target-url JDBC [--report 文件]` | 可选步骤：为老用户发放 1 元默认 AI 额度。**待 L5.1 完成后执行**，目前返回 3 并在报告中标明 `pending-L5.1` |

退出码：0 成功；1 报告中有 ERROR 或执行失败（已整体回滚）；2 参数错误；3 步骤尚不可执行。
`--target-user` 默认为 `quizforge`。报告写入不存在的文件（不覆盖），同时输出到标准输出。

## 规则

- **源只读：** SQLite 以只读模式打开；读取前后计算副本的 SHA-256，不一致即失败。请用停写后的在线备份（单个文件，不带 `-wal`）。
- **结构核对：** `users`、`user_phones`、`unverified_contacts`、`user_blocks` 的列必须与旧版完全一致（兼容旧 Node 库 `users.email NOT NULL`），未知列直接拒绝。
- **字段映射**（09 第 1.1 节）：密码哈希逐字节原样写入、`password_algo = scrypt-v1`；邮箱去首尾空白并转小写；`users.created`（ISO-8601）截断到毫秒；`user_phones.verified` 为 0 时未验证，否则为验证时刻（写入 `verified_at`）；只迁移与当前联系方式一致的未验证标记。
- **发现代码**（09 第 4.4 节）：ERROR（阻止迁移）— `EMAIL_COLLISION_CI`（V2 排序规则 `utf8mb4_0900_ai_ci` 下只差大小写或重音）、`EMAIL_INVALID`、`PHONE_FORMAT_INVALID`、`NAME_INVALID` / `NAME_TOO_LONG`、`REASON_TOO_LONG`、`TIMESTAMP_UNPARSABLE`、`ID_FORMAT_INVALID`、`ORPHAN_ROW`、`VERIFY_MISMATCH`、`LOGIN_CHECK_FAILED`、`LOGIN_SAMPLE_INCOMPLETE`；WARN — `EMAIL_NOT_NORMALIZED`、`PASSWORD_FORMAT_INVALID`、`NO_LOGIN_IDENTIFIER`、`TARGET_EXTRA_ACCOUNT`；INFO — `STALE_UNVERIFIED_CONTACT`。
- **幂等与回滚**（09 第 5.3 节）：`account_user`、`account_user_phone`、`account_unverified_contact`、`account_block`、`auth_session` 必须全部为空，否则拒绝（`TARGET_NOT_EMPTY`）；目标库须已由当前版本的 V2 建表（Flyway ≥ V9）。全部写入在一个事务内，提交前在同一事务里逐用户核对，任何失败整体回滚。需要重来时删除并重建目标库。
- **报告脱敏：** ID 前 8 位、昵称首字、`a***@example.com`、`+86138****8000`，不含密码哈希。

## 原密码登录抽查

`--known-passwords` 指向 git 忽略的 `tools/migration/local/` 下的 JSON 文件，由作者在旧系统停写**之前**准备：

```json
[{"identifier": "admin@example.com", "password": "…", "expect": "login"},
 {"identifier": "138 0013 8000",     "password": "…", "expect": "login"},
 {"identifier": "blocked@example.com","password": "…", "expect": "blocked"}]
```

必须至少包含一个能登录的**邮箱账号**和一个能登录的**只有手机号的账号**，否则报告 `LOGIN_SAMPLE_INCOMPLETE`。
不给 `--login-url` 时离线核对（与 V2 `PasswordHasher` 相同的 scrypt 算法，并检查封禁状态）；给出 V2 地址
（例如 `http://localhost:8088`）时向 `POST /api/auth/login` 发起真实登录：200 为能登录，401 为被拒绝。
真实登录受 V2 的认证限频（每 IP 每分钟 12 次）约束，样本较多时分批执行。

## 默认 AI 额度（待 L5.1）

需求 v1.12：老用户在迁移时获得 1 元（10000 单位）默认额度并写一条流水。L5.1 尚未创建 AI 表，本工具**不创建**。
L5.1 完成后需要：`ai_account`（每用户余额）、`ai_ledger`（`kind = migration`、`ref = legacy:<userId>`、唯一约束
`(kind, ref)`），以及 `DefaultCredit.Granter` 的实现（同一事务内：锁余额行 → 加额度 → 插入流水；已发放过的用户跳过），
再在 `grant-default-credit` 中接入。

## 私人数据

真实旧库副本、已知密码文件与报告**只**放在 `tools/migration/local/`（Git 与 Docker build context 都忽略）。
测试只使用测试中现场构造的合成 SQLite 库。
