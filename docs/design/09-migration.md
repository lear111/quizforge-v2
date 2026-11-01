# 详细设计 09：旧数据迁移规格（账号与 AI 余额）

> **文档版本：** v0.1（草稿，待作者评审）
> **日期：** 2026-11-01
> **依据：** [需求 FR-09（含 v1.11 更正）、C-06](../requirements/01-requirements.md)，[上线路线图 L7](../plan/01-launch-roadmap.md)，[ADR-008](../adr/ADR-008-data-migration.md)，[详细设计 08 第 0、1 节](08-ai.md)，[遗留契约 C-06、V-2](../legacy/01-contracts.md)，[旧数据字典](../legacy/02-data-dictionary.md)
> **旧源码版本：** `lear111/quizforge_hub` @ `83d6dfd`（只读查阅：`java-hub/src/main/resources/*.sql`、`account/`、`ai/`、`admin/AdminService.java`、`storage/HubDatabase.java`、`test/fixtures/legacy-hub.sql`、`docs/JAVA_ACCOUNTS.md`、`docs/PHONE_AUTH.md`、`docs/HUB_CONNECT_AI.md`）
> **范围：** 只迁移用户身份、AI 余额及全部流水（需求 v1.3 起）。题库、拓展白名单、练习数据、设备令牌、会话都不迁移。

本文是迁移工具（L7）的规格说明，只描述“迁什么、怎么对应、怎么校验、怎么执行与回退”，不包含实现代码。第 7 节列出需要作者决定的事项，**在作者决定之前，相关条目按“建议”处理，不视为已定**。

---

## ⚠️ 2026-11-01 范围变更（需求 v1.12，以本节为准）

作者决定：**只迁移账号与密码**，保证老用户能用原密码正常登录。

| 第 7 节事项 | 决定 |
| --- | --- |
| Q-1 旧流水如何进入 V2 | **不迁移**：旧 AI 余额、流水、每日用量都不迁移，也不建归档表 |
| Q-2 余额为 0 的用户 | 所有老用户在迁移时获得 **1 元**默认额度，写一条流水；新用户注册时同样获得 1 元（`ai_signup_bonus` 保留，金额 1 元） |
| Q-3 旧余额不一致以谁为准 | 不适用 |
| Q-4 兑换码 | **不迁移**，V2 兑换码从零开始（含未兑换的码） |
| Q-5 兑换码密文 | 不适用；V2 不需要旧主密钥 |
| Q-6 手机号验证时间 | **保留**：新增 `verified_at`，迁移旧库的验证时刻 |
| Q-7 注册赠送 | **保留**，金额 1 元 |
| Q-8 登录与注册设置 | 保留（L4） |
| Q-9 观察窗口暂停兑换码 | 不适用（V2 兑换码数据清零） |

因此第 1.2 节（AI 字段映射）、第 3 节（AI 余额）以及第 4 节中与 AI 余额相关的校验项**作废**，仅保留作为旧库结构的参考。第 1.1 节（账号）、第 2 节（密码与登录）、第 4、5 节中与账号相关的部分继续有效；校验改为：用户数、账号字段逐项一致、每个用户恰好一条 1 元默认额度流水、原密码登录抽查（含只有手机号的用户）。

## 0. 源数据的位置与代际

| 项目 | 说明 |
| --- | --- |
| 源数据库 | 旧 Hub 数据目录下的 `hub.sqlite`（SQLite，WAL 模式）。账号、AI 余额、兑换码都在这一个库中 |
| 不读取 | 练习服务的另一个 `hub.sqlite`（其中 `users`、`sessions` 只是 Hub 身份的副本，以 Hub 库为准）、`cloud-content/` 工作区、`files/`、`downloads/` |
| 代际 | 生产站仍运行**旧 Node Hub**（旧 README），Node Hub 源码已从旧仓库删除。从 `test/fixtures/legacy-hub.sql`（由空的 Node Hub 库生成）看，账号与 AI 相关表的结构与 Java Hub 相同，Java Hub 只在启动时做了三处增量升级（见下） |

Java Hub 对 Node 库做的增量升级（`HubDatabase.java`），迁移工具必须**兼容升级前与升级后两种形态**，但**不得修改源库**：

| 升级 | 对迁移的影响 |
| --- | --- |
| `users.email` 从 `NOT NULL` 改为可空（为只有手机号的用户） | 两种形态都按“可能为 NULL”读取 |
| `ai_redeem_codes` 补列 `code_cipher`、`seq`，并按 `created, id` 顺序回填 `seq` | 缺列时：`code_cipher` 视为 NULL；`seq` 按同样规则（`created, id` 升序，从 1 开始）在内存中生成 |
| `official_ai_usage` 补列 `points INTEGER NOT NULL DEFAULT 0` | **缺列时无法校验旧余额**，按第 4.2 节报为错误，交作者决定（第 7 节 Q-3） |

**结论：** 生产副本的实际代际仍须在演练时用 `inspect` 确认（数据字典 D-1），本文的映射对两代都适用。

---

## 1. 字段映射

约定：

- 旧库时间有两种：`users.created` 是 ISO-8601 文本（Java 写 `Instant.toString()`，可能带微秒或纳秒）；其余是 **Unix 毫秒整数**。V2 统一为 `DATETIME(3)`，UTC，**截断到毫秒**。无法解析时报错并停止（不猜测）。
- 旧库的 SHA-256 摘要是 64 位小写十六进制文本，V2 存为 `BINARY(32)`（数据库设计第 1 节约定）。
- 旧 ID 原样保留（数据库设计：迁移数据保留旧 ID）。旧 ID 均为 UUID v4 字符串，`inspect` 检查格式，不符合的报错。
- 金额单位不变：整数，**10000 = 1 元**（0.0001 元为一个单位）。

### 1.1 账号

#### `users` → `account_user`

| 旧字段 | 类型 | V2 字段 | 转换 |
| --- | --- | --- | --- |
| `id` | TEXT PK | `id` CHAR(36) | 原样 |
| `email` | TEXT UNIQUE，可空 | `email` VARCHAR(254)，可空 | 原样。旧系统写入前已去首尾空白并 `toLowerCase(Locale.ROOT)`；`inspect` 检查是否全部已规范化，**不是规范形式的报警告**，迁移时写入规范形式（否则 V2 登录时规范化后的输入查不到） |
| `name` | TEXT | `name` VARCHAR(64) | 原样。旧 Java 注册限 30 字；超过 64 个字符的报错 |
| `password` | TEXT（`<salt>:<hash>`） | `password_hash` VARCHAR(255) | **逐字节原样**（第 2 节） |
| — | — | `password_algo` | 固定 `'scrypt-v1'` |
| `admin` | INTEGER 0/1 | `is_admin` BOOLEAN | `admin <> 0` |
| `created` | TEXT（ISO-8601） | `created_at` | 解析后截断到毫秒 |
| — | — | `updated_at` | 等于 `created_at` |
| — | — | `version` | 0 |

注意：V2 的排序规则是 `utf8mb4_0900_ai_ci`（不区分大小写与重音），SQLite 的 `UNIQUE` 区分。两个旧邮箱只差大小写或重音时，在 V2 中会违反 `uk_user_email`。`inspect` 必须预先检查，命中则报错（交作者处理，不自动合并账号）。

#### `user_phones` → `account_user_phone`

| 旧字段 | V2 字段 | 转换 |
| --- | --- | --- |
| `user_id` PK | `user_id` | 原样 |
| `phone` UNIQUE | `phone` VARCHAR(32) | 原样。旧格式统一为 `+86` + 11 位（`Contact.parse`），`inspect` 校验 `^\+861[3-9]\d{9}$`，不符合的报错 |
| `verified`（0 或验证时刻毫秒） | `verified` BOOLEAN | `verified <> 0` |
| 同上 | **新增** `verified_at` DATETIME(3) NULL | `verified = 0` 时 NULL，否则转换为时间（建议新增此列，避免丢弃验证时间；第 7 节 Q-6） |

#### `unverified_contacts` → **新增** `account_unverified_contact`

旧系统在“注册不要求验证码”时，把未验证的联系方式记入此表；用户资料返回 `unverifiedContacts`，之后补验证会删除该行。V2 目前没有此表。数据库设计第 4.1 节已预留表名，按旧表一一对应：

| 旧字段 | V2 字段 | 转换 |
| --- | --- | --- |
| `user_id` | `user_id` CHAR(36) | 原样 |
| `channel`（`email` / `phone`） | `channel` VARCHAR(8) | 原样；其他值报错 |
| `contact` | `contact` VARCHAR(254) | 原样 |
| 主键 `(user_id, channel)` | 同 | |

只迁移与用户**当前**联系方式一致的行（旧 `AccountStore.profile` 只认这种行）；不一致的行是历史残留，计入报告的 `INFO`，不迁移。

#### `user_blocks` → `account_block`

| 旧字段 | V2 字段 | 转换 |
| --- | --- | --- |
| `user_id` PK | `user_id` | 原样 |
| `actor_id` | `actor_id` | 原样（没有外键；封禁人可能是已不存在的管理员，仍原样保留） |
| `reason` | `reason` VARCHAR(500) | 原样；超长报错 |
| `created`（毫秒） | `created_at` | 转换 |

#### 账号相关、不迁移的表

| 旧表 | 处理 | 理由 |
| --- | --- | --- |
| `sessions` | 不迁移 | FR-09；用户在 V2 重新登录一次 |
| `connect_devices`、`connect_codes` | 不迁移 | v1.3：客户端在 V2 上线后重新授权 |
| `email_codes`、`email_sends`、`contact_codes`、`contact_sends` | 不迁移 | 临时数据 |
| `registration_policy`、`authentication_policy` | 不迁移数据；切换前由作者在 V2 中按需重新设置 | 是配置，不是用户数据；V2 的注册登录策略由 L4 实现（第 7 节 Q-8） |
| `invitations`、`invitation_audit` | 不迁移 | 不在 FR-09 范围；V2 是否保留邀请码注册未定（第 7 节 Q-8） |
| `admin_audit`、`admin_contact`、`admin_meta` | 不迁移 | 不在 FR-09 范围；切换前把旧库副本归档即可查阅 |

### 1.2 AI 余额与流水

#### 旧表结构与语义（已核对源码）

| 旧表 | 字段 | 语义 |
| --- | --- | --- |
| `ai_points` | `user_id` PK, `balance`, `updated` | 当前余额。没有行视为 0。旧系统扣费用 `MAX(balance - ?, 0)`，管理员设置值限 ≥ 0，**余额不会为负** |
| `ai_points_ledger` | `id`, `user_id`, `delta`, `kind`, `ref`, `created` | 余额变动流水，只有三种 `kind`：`signup`（注册赠送，`ref` = 文本“新用户赠送”）、`redeem`（兑换，`ref` = 兑换码 `id`）、`admin`（管理员把余额**设为**某值，`delta` = 新值 − 旧值，`ref` = 操作原因文本） |
| `official_ai_usage` | `user_id`, `day`（`YYYY-MM-DD`，上海时区）, `calls`, `tokens`, `points` | 每用户每天的调用次数、token 数和**实际扣除**的金额（`points` = `min(费用, 当时余额)`） |
| `ai_redeem_codes` | `id`, `digest`, `prefix`, `points`, `note`, `created_by`, `created`, `expires`, `revoked`, `redeemed`, `user_id`, `code_cipher`, `seq` | 兑换码。状态：`redeemed` 非空 → 已兑换；否则 `revoked` 非空 → 已停用；否则 `expires <= now` → 已过期；否则可用 |
| `ai_signup_bonus` | `amount` | 新用户赠送金额（配置） |

因为每次调用的扣费只按天汇总在 `official_ai_usage`，不进流水，旧库的恒等式是：

```text
ai_points.balance = Σ ai_points_ledger.delta − Σ official_ai_usage.points      （按用户）
```

（`admin` 流水的 `delta` 以“当时的余额”为基准计算，已包含此前的扣费，因此上式在旧代码下成立。）

#### V2 目标表（需新增 Flyway 迁移）

V2 目前没有 AI 表。下列结构按设计 08 第 1 节给出，**应由 L5.1 的 PR 创建**；如果迁移工具先于 L5.1 实现，则由单独的 PR 先创建这些表，结构以本文和设计 08 为准，二者不一致时先改设计文档。版本号取实现时的下一个空闲版本（当前最新为 `V6`）。

```sql
-- V<n>__ai_balance_and_ledger.sql（示意，最终 DDL 以实现 PR 为准）
CREATE TABLE ai_account (                      -- 每用户一行；当前余额的唯一来源，扣费时 FOR UPDATE
  user_id     CHAR(36)    NOT NULL,
  balance     BIGINT      NOT NULL,            -- 10000 = 1 元；可以为负（设计 08 §1.2-4）
  updated_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_ledger (                       -- 只插入，不修改、不删除
  id             CHAR(36)     NOT NULL,
  user_id        CHAR(36)     NOT NULL,
  delta          BIGINT       NOT NULL,
  balance_after  BIGINT       NOT NULL,
  kind           VARCHAR(16)  NOT NULL,        -- redeem / ai_call / admin / migration / refund
  ref            VARCHAR(64)  NULL,            -- 兑换码 id、AI 任务 id、'legacy:<userId>' 等
  actor_id       CHAR(36)     NULL,            -- 管理员调整的操作人
  reason         VARCHAR(400) NULL,            -- 管理员调整的原因
  created_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_ledger_source (kind, ref),
  KEY idx_ai_ledger_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_redeem_code (                  -- V2 充值也只用兑换码（设计 08 §0）
  id           CHAR(36)     NOT NULL,
  seq          BIGINT       NOT NULL,          -- 管理界面显示的编号
  digest       BINARY(32)   NOT NULL,          -- SHA-256(规范化后的 16 位码体)
  prefix       VARCHAR(8)   NOT NULL,          -- 'AI-XXXX'
  points       BIGINT       NOT NULL,
  note         VARCHAR(60)  NOT NULL,
  created_by   CHAR(36)     NOT NULL,
  created_at   DATETIME(3)  NOT NULL,
  expires_at   DATETIME(3)  NOT NULL,
  revoked_at   DATETIME(3)  NULL,
  redeemed_at  DATETIME(3)  NULL,
  redeemed_by  CHAR(36)     NULL,
  code_cipher  VARCHAR(255) NULL,              -- 完整码的 AES-256-GCM 密文（是否保留见 Q-5）
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_redeem_digest (digest),
  UNIQUE KEY uk_ai_redeem_seq (seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 旧流水与每日用量的只读归档（建议方案，见 §3.1 与 Q-1）
CREATE TABLE ai_legacy_ledger (
  id          CHAR(36)     NOT NULL,           -- 旧 ai_points_ledger.id
  user_id     CHAR(36)     NOT NULL,
  delta       BIGINT       NOT NULL,
  kind        VARCHAR(16)  NOT NULL,           -- signup / redeem / admin（原样）
  ref         VARCHAR(500) NULL,               -- 原样；admin 的 ref 是原因文本
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_legacy_ledger_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_legacy_usage_daily (
  user_id  CHAR(36) NOT NULL,
  day      DATE     NOT NULL,                  -- 原样（上海时区的日期）
  calls    BIGINT   NOT NULL,
  tokens   BIGINT   NOT NULL,
  points   BIGINT   NOT NULL,
  PRIMARY KEY (user_id, day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

> 表名说明：设计 08 用 `ai_account`，数据库设计第 4.5 节用 `ai_wallet`（第 6 节 I-4）。本文按较新的设计 08。

#### 字段对应

| 旧 | V2 | 转换 |
| --- | --- | --- |
| `ai_points.user_id` / `balance` | `ai_account.user_id` / `balance` | 每个迁移用户都建一行；没有旧行的余额为 0。`updated_at` = 迁移时刻 |
| （按用户）旧余额 | `ai_ledger` 一行：`kind='migration'`、`delta=旧余额`、`balance_after=旧余额`、`ref='legacy:<userId>'`、`created_at=迁移时刻`、`id` = 由 `userId` 派生的确定性 UUID（v5，命名空间固定） | 第 3 节 |
| `ai_points_ledger.*` | `ai_legacy_ledger.*` | 逐行原样；`created` 毫秒 → `created_at` |
| `official_ai_usage.*` | `ai_legacy_usage_daily.*` | 逐行原样；`day` 文本 → `DATE` |
| `ai_redeem_codes.id`、`seq`、`prefix`、`points`、`note`、`created_by` | 同名字段 | 原样（缺 `seq` 时按第 0 节生成） |
| `ai_redeem_codes.digest`（hex） | `digest` BINARY(32) | hex 解码 |
| `created`、`expires`、`revoked`、`redeemed`（毫秒） | `created_at`、`expires_at`、`revoked_at`、`redeemed_at` | 转换 |
| `ai_redeem_codes.user_id` | `redeemed_by` | 原样 |
| `code_cipher` | `code_cipher` | 按 Q-5 决定原样保留或置 NULL |

#### AI 相关、不迁移的表

| 旧表 | 处理 | 理由 |
| --- | --- | --- |
| `ai_signup_bonus` | 不迁移数据；值记入迁移报告，由作者在 V2 配置中设置 | 配置；V2 是否保留注册赠送未定（Q-7） |
| `official_ai_config`、`ai_upstreams` | 不迁移；上游 Key 由管理员在 V2 重新录入 | 不在 FR-09 范围；不迁移就不需要把旧主密钥 `OFFICIAL_AI_MASTER_KEY` 带进 V2（Q-5 若保留兑换码密文则仍需要） |

---

## 2. 密码与登录

### 2.1 旧哈希算法（待核实项 V-2 已核实）

| 项目 | 规则 | 出处 |
| --- | --- | --- |
| 算法 | scrypt，`N=16384, r=8, p=1, dkLen=64` | `AccountCrypto.derive`；Node 侧为 `crypto.scryptSync(password, salt, 64)` 的默认参数 |
| salt 生成 | `SecureRandom` / `randomBytes` 生成 **16 字节**，编码为 **32 位小写十六进制文本** | `AccountCrypto.password`：`hex(random(16))`；`test/java-account-interop.test.mjs`：`randomBytes(16).toString('hex')` |
| salt 参与计算的方式 | **十六进制文本本身的 UTF-8 字节**（32 字节 ASCII），**不是**解码后的 16 字节 | `SCrypt.generate(utf8(password), utf8(salt), …)`；Node 把字符串 salt 按 UTF-8 处理 |
| 密码编码 | UTF-8；孤立的 UTF-16 代理字符替换为 U+FFFD（与 Node 一致） | `AccountCrypto.utf8` |
| 存储格式 | `<32 位小写 hex salt>:<128 位小写 hex 摘要>`，正则 `^[a-f0-9]{32}:[a-f0-9]{128}$` | `AccountCrypto.matches` |
| 比较 | 常量时间比较；格式不合法时仍用假哈希算一次，耗时一致 | 同上 |

### 2.2 V2 如何验证旧密码

V2 的 `PasswordHasher`（M1 已实现）与上表**完全一致**：同样的参数、同样把 salt 文本按 UTF-8 参与计算、同样的 U+FFFD 替换和格式检查。因此：

- 迁移时 `password` 字段**逐字节原样**写入 `account_user.password_hash`，`password_algo = 'scrypt-v1'`，**不需要重新计算、也不需要用户重置密码**（C-06）。
- 迁移工具不接触任何明文密码。
- V2 登录不校验密码最短长度（旧账号可能短于 V2 的 8 位要求）；上限 1024 个字符，大于旧系统的 128，不会拒绝旧密码。
- 以后若更换算法（例如 Argon2），按 C-06 在用户下次成功登录时升级，`password_algo` 记录新值；与本次迁移无关。

**测试向量**（由 Node `crypto.scryptSync` 生成，并已用 Python `hashlib.scrypt` 独立复算一致；迁移工具与 `PasswordHasher` 的测试应断言 V2 能验证通过）：

| 明文 | 存储值 |
| --- | --- |
| `quizforge-legacy-1` | `00112233445566778899aabbccddeeff:cfc7a534abad344268842ad61f529c79397327fcf2f6bb1dd07e19e60b4e5ceaff127c1119f98f7ac7f461818db1665ff590bb457edf46c32441fecfa2dd2738` |
| `密码测试😀abc` | `00112233445566778899aabbccddeeff:7c2e44f1e717691b5e11e226043bbd79d3088689339b28b7ff6c6596dcd68b2b6c3333edc48f4fd31d03c882547d76ea28a68ad67e704e31644814757ab2d68a` |

`password` 不符合存储格式的账号在旧系统中本来就无法登录：照常迁移（保持“迁移前后行为一致”），在报告中列为 `WARN PASSWORD_FORMAT_INVALID`。

### 2.3 只有手机号、没有邮箱的用户

- **数据层面没有障碍：** `account_user.email` 可空，`account_user_phone` 已在 V1 迁移中创建，迁移工具照常写入两张表。
- **登录层面依赖 Issue #20（路线图 L4，FR-01）：** V2 目前的 `AuthService.login` 只接受邮箱。手机号登录（`identifier` 识别、`+86` 规范化规则与旧 `Contact.parse` 一致、`account_user JOIN account_user_phone` 查找）未实现之前，这些用户迁过来也无法登录。
- **依赖关系：**

  ```text
  迁移工具的实现与演练  ──不依赖──  Issue #20
  正式迁移（切换上线）  ──必须在──  Issue #20 合并之后
  验收场景 11 的“手机号用户用原密码登录”抽查 ──依赖── Issue #20
  ```

  即：迁移工具可以先写、先在副本上演练（演练时手机号用户的登录抽查标为“阻塞于 #20”）；**#20 未合并时不得正式切换**。
- 同时登记了邮箱和手机号的用户，两种方式在 #20 之后都可登录；#20 之前只能用邮箱。
- 既没有邮箱也没有手机号的账号（旧系统正常流程不会产生）：照常迁移，报告 `WARN NO_LOGIN_IDENTIFIER`，这类账号在新旧系统中都无法登录。

### 2.4 被封禁、已停用的用户

- 旧系统**只有一种停用方式**：`user_blocks` 中有行即封禁。登录提示“账号或密码不正确，或账号已停用”中的“停用”指的就是封禁；源码中没有删除用户或另外的“停用”标记。
- **迁移方式：** 照常迁移账号、余额与流水，同时迁移 `account_block` 行，迁移后保持封禁。解封由管理员在 V2 中操作（L4）。
- **V2 现状与依赖：** V2 的会话校验已排除被封禁用户（`SessionMapper` 的 `NOT EXISTS account_block`），所以被封禁用户即使登录也无法使用；但 `AuthService.login` 目前仍会为其创建会话并返回成功，不符合“封禁用户不能登录”（Issue #20 第 5 项，L4）。**正式切换同样要求 #20 合并**。
- 旧系统封禁时会撤销该用户的设备令牌；设备令牌本次不迁移，没有额外处理。

---

## 3. AI 余额

### 3.1 迁移方式

1. 对每个迁移用户，`旧余额 = ai_points.balance`（没有行为 0）。
2. 在 V2 中写入 `ai_account(user_id, balance = 旧余额)`，并写入**一条** `ai_ledger`：`kind = 'migration'`、`delta = 旧余额`、`balance_after = 旧余额`、`ref = 'legacy:<userId>'`。余额为 0 的用户也写这一条（`delta = 0`），使“每个用户恰好一条 migration 流水”可以直接校验（Q-2）。
3. 旧的 `ai_points_ledger` 与 `official_ai_usage` **全部逐行**原样写入只读归档表 `ai_legacy_ledger`、`ai_legacy_usage_daily`，满足 FR-09“全部流水”的要求，同时不破坏 V2 的 `余额 = Σ ai_ledger.delta`。
4. 迁移后 V2 的恒等式 `ai_account.balance = Σ ai_ledger.delta` 对每个用户成立（此时就是那一条 migration 流水）。

**为什么不把旧流水直接写进 `ai_ledger`：** 旧流水不含每次调用的扣费，直接写入后 `Σ delta ≠ 余额`；若再补一条冲正，或把每日用量拆成负数流水，都会让 V2 流水中出现“不是真实单笔操作”的记录，`balance_after` 也无法如实重建。归档表把“旧系统的历史”和“V2 的账本”分开，校验最简单。这是建议方案，替代方案见 Q-1。

### 3.2 旧余额与旧恒等式不一致时

迁移前逐用户计算 `差额 = ai_points.balance − (Σ ledger.delta − Σ usage.points)`：

- 差额为 0：正常。
- 差额不为 0：报 `ERROR BALANCE_INVARIANT_MISMATCH`，列出该用户的三项数字。**迁移金额仍以 `ai_points.balance`（用户在旧系统看到的余额）为准**，但在作者逐条确认前不得正式切换（Q-3）。可能原因：早期 Node 版本 `official_ai_usage` 尚无 `points` 列、早期扣费规则不同、手工改库。
- 旧余额为负（旧代码不会产生）：报 `ERROR NEGATIVE_BALANCE`。
- `ai_points_ledger` 中出现 `signup` / `redeem` / `admin` 以外的 `kind`：报 `WARN UNKNOWN_LEDGER_KIND`，仍原样归档并计入上式。
- 流水、用量、余额行指向不存在的用户（旧库开启了外键，正常不会出现）：报 `ERROR ORPHAN_ROW`。

### 3.3 兑换码

旧系统充值只能用兑换码，V2 沿用（设计 08 第 0 节）。兑换码的摘要算法必须与旧系统一致，迁移后的码才能在 V2 继续识别：

```text
规范化：转大写 → 去掉空白、全角空白、BOM 和 '-' → 去掉开头的 "AI" → O→0、I→1、L→1
        结果必须匹配 ^[0-9A-HJKMNP-TV-Z]{16}$
摘要：  SHA-256(规范化后的 16 位码体的 UTF-8 字节)，小写 hex
```

（向量：码体 `0123456789ABCDEF` → `2125b2c332b1113aae9bfc5e9f7e3b4c91d828cb942c2df1eeb02502eccae9e9`。）

| 旧状态 | 判断 | 建议处理 |
| --- | --- | --- |
| 已兑换 | `redeemed IS NOT NULL` | **迁移**到 `ai_redeem_code`（`redeemed_at`、`redeemed_by` 原样）。作用：①旧流水 `redeem` 的 `ref` 仍能对应到码；②同一个码在 V2 再输入时被识别为“已使用”，而不是可兑换。金额已体现在旧余额中，**不再入账** |
| 未兑换、未停用、未过期（可用） | 其余 | **迁移并保持可兑换**：持码人（可能已付费购买）上线后仍可在 V2 兑换（Q-4） |
| 已停用（作废） | `redeemed IS NULL AND revoked IS NOT NULL` | 迁移，保持停用；V2 不可兑换 |
| 已过期 | `redeemed IS NULL AND revoked IS NULL AND expires <= now` | 迁移，`expires_at` 原样，V2 按时间判断为过期 |

兑换码不是用户数据，但它与“全部流水”直接相关，且未兑换码对应真实的未来负债，因此建议全部迁移；是否迁移、未兑换码是否继续有效，见 Q-4。完整码密文 `code_cipher` 是否保留见 Q-5。

---

## 4. 校验方案

### 4.1 工具的模式

沿用 ADR-008：迁移工具是 V2 仓库中的**独立命令行程序**，不进入线上应用。

| 模式 | 输入 | 作用 |
| --- | --- | --- |
| `inspect` | 旧库副本（只读打开） | 结构检查、统计、迁移前逐用户快照（4.2），输出差异报告 |
| `migrate --dry-run` | 旧库副本 + 一次性临时 MySQL | 完整执行迁移并运行 `verify`，最后回滚事务 |
| `migrate` | 旧库副本 + 目标 V2 库 | 正式迁移（第 5 节），结束后自动运行 `verify` |
| `verify` | 旧库副本 + 目标 V2 库 | 迁移后逐用户核对（4.3），可单独反复运行 |

源库一律以只读方式打开**副本**（SQLite URI `mode=ro`），不打开生产原件。

### 4.2 迁移前（`inspect`）

1. `PRAGMA integrity_check` 为 `ok`，`PRAGMA foreign_key_check` 为空；否则停止。
2. 结构检查：本文涉及的 9 张表存在；各表列集合等于第 0 节允许的两种形态之一；出现未知列或缺少必需列时停止（不猜测，与旧 `HubDatabase` 的“未知结构拒绝迁移”一致）。
3. 逐用户计算并记录：ID、邮箱是否存在 / 是否已规范化、手机号是否存在 / 是否已验证、是否管理员、是否封禁、密码格式是否合法、`ai_points.balance`、`Σ ledger.delta`（按 kind 分项）、流水条数、`Σ usage.points`、用量天数、3.2 的差额。
4. 全局统计：用户数、有邮箱 / 只有手机号 / 两者都有 / 都没有的用户数、管理员数、封禁数、余额合计、兑换码按状态的数量与金额、`ai_signup_bonus.amount`。
5. 冲突预检：V2 排序规则下邮箱是否重复、手机号格式、名字长度、时间可否解析、ID 格式。
6. 计算旧库副本文件的 SHA-256，写入报告，作为后续 `migrate` / `verify` 的“源指纹”。

### 4.3 迁移后（`verify`）

对旧库中的**每一个用户**逐项比较（17 个用户，全量比对，不抽样）：

| 项目 | 旧库 | V2 | 规则 |
| --- | --- | --- | --- |
| 用户集合 | `users.id` | `account_user.id` | 两边集合相等 |
| 邮箱、名字、管理员 | `users` | `account_user` | 按第 1 节转换后相等 |
| 密码 | `users.password` | `account_user.password_hash` | **逐字节相等**；`password_algo='scrypt-v1'` |
| 创建时间 | `users.created` | `created_at` | 毫秒精度相等 |
| 手机号 | `user_phones` | `account_user_phone` | 有无一致；号码相等；`verified` 与 `verified_at` 按转换规则相等 |
| 未验证联系方式 | `unverified_contacts`（仅当前联系方式） | `account_unverified_contact` | 集合相等 |
| 封禁 | `user_blocks` | `account_block` | 有无一致；字段相等 |
| 余额 | `ai_points.balance`（无行为 0） | `ai_account.balance` | 相等 |
| migration 流水 | — | `ai_ledger WHERE kind='migration'` | 每用户恰好 1 条，`delta = balance_after = 旧余额` |
| V2 恒等式 | — | `Σ ai_ledger.delta` | 等于 `ai_account.balance` |
| 旧流水归档 | `ai_points_ledger` | `ai_legacy_ledger` | 条数、`Σ delta`（按 kind）、逐行 ID 集合相等 |
| 旧用量归档 | `official_ai_usage` | `ai_legacy_usage_daily` | 天数、`Σ calls`、`Σ tokens`、`Σ points` 相等 |

全局：用户数、封禁数、管理员数、余额合计相等；兑换码按状态的数量与金额相等，摘要集合相等；V2 中不存在旧库没有的用户、余额或流水行（迁移前目标为空库，见第 5 节）。

### 4.4 差异报告格式

报告写入 git 忽略的 `local/migration/<时间戳>/`，**不提交、不上传 CI**。两份文件：

- `report.json`：机器可读，供重复比对；
- `report.md`：给作者阅读的摘要。

报告中**不得出现**密码哈希、完整邮箱和手机号、兑换码明文或密文。联系方式脱敏为 `a***@example.com`、`+86138****8000`；用户以 ID 前 8 位加脱敏名字标识。

`report.json` 结构：

```json
{
  "mode": "verify",
  "toolVersion": "<git commit>",
  "source": { "sha256": "<旧库副本摘要>", "generation": "node|java", "schemaVariant": "with-points|without-points" },
  "target": { "flywayVersion": "<n>", "database": "<库名>" },
  "startedAt": "2026-…Z", "finishedAt": "2026-…Z",
  "summary": {
    "users": { "source": 17, "target": 17 },
    "phoneOnly": 3, "blocked": 1, "admins": 1,
    "balanceTotal": { "source": 123400, "target": 123400 },
    "redeemCodes": { "redeemed": 10, "available": 5, "revoked": 2, "expired": 1 },
    "errors": 0, "warnings": 1, "infos": 2
  },
  "users": [
    {
      "id": "3f1c…", "label": "张*", "email": "a***@example.com", "phone": null,
      "checks": { "identity": "ok", "password": "ok", "phone": "n/a", "block": "ok",
                  "balance": { "source": 5000, "target": 5000, "status": "ok" },
                  "legacyLedger": { "rows": 3, "sum": 15000, "status": "ok" },
                  "legacyUsage": { "days": 7, "points": 10000, "status": "ok" } }
    }
  ],
  "findings": [
    { "severity": "WARN", "code": "PASSWORD_FORMAT_INVALID", "userId": "9a0b…", "detail": "存储值不符合 <salt>:<hash> 格式，旧系统同样无法登录" }
  ]
}
```

严重级别：

| 级别 | 含义 | 对切换的影响 |
| --- | --- | --- |
| `ERROR` | 数据会丢失、出错或无法迁移 | **阻止切换**；必须修复或由作者书面决定后重跑 |
| `WARN` | 可迁移，但新旧行为或数据有可解释的差别 | 作者在报告中逐条签注“已知悉”后才能切换 |
| `INFO` | 统计或不迁移的残留数据 | 不阻止 |

已定义的代码（实现时可补充，补充须同步本表）：`SCHEMA_UNKNOWN`、`INTEGRITY_FAILED`、`ORPHAN_ROW`、`ID_FORMAT_INVALID`、`TIMESTAMP_UNPARSABLE`、`EMAIL_NOT_NORMALIZED`、`EMAIL_COLLISION_CI`、`PHONE_FORMAT_INVALID`、`NAME_TOO_LONG`、`REASON_TOO_LONG`、`PASSWORD_FORMAT_INVALID`、`NO_LOGIN_IDENTIFIER`、`BALANCE_INVARIANT_MISMATCH`、`NEGATIVE_BALANCE`、`USAGE_POINTS_MISSING`、`UNKNOWN_LEDGER_KIND`、`STALE_UNVERIFIED_CONTACT`、`TARGET_NOT_EMPTY`、`VERIFY_MISMATCH`。

### 4.5 抽查：原密码登录与余额一致

工具无法得知用户的明文密码，所以“原密码可登录”分两层证明：

1. **算法层（自动、全量）：** 测试中用第 2.1 节的 Node 向量证明 V2 能验证旧哈希；`verify` 证明每个用户的哈希逐字节原样迁入。两者合起来，等价于“每个旧密码在 V2 都能通过”。
2. **端到端（手动、抽查）：** 在旧系统停写**之前**，由作者在旧系统中准备并记录（记录放在 `local/`）以下账号的明文密码和余额：
   | 账号 | 用途 |
   | --- | --- |
   | 作者本人的管理员账号 | 管理员标记迁移 |
   | 一个只有邮箱的测试账号，兑换过一个码 | 邮箱登录、余额 |
   | 一个**只有手机号**的测试账号 | 手机号登录（依赖 #20） |
   | 一个邮箱 + 手机号都有的测试账号 | 两种方式登录同一账号 |
   | 一个被封禁的测试账号 | 迁移后仍不能登录 |
   | 一个有用量扣费的账号 | 余额 = 旧恒等式结果 |

   停写前在旧系统中对每个账号截取 `GET /api/account/ai` 的 `balance`（存 `local/`）；迁移后在 V2 中用相同的明文密码登录（邮箱、手机号三种写法 `138 0013 8000` / `+8613800138000` / `8613800138000` 各试一次），核对余额等于记录值、被封禁账号返回 401。结果写入 `report.md` 的“抽查”一节。
3. 切换后，可请一两位真实用户自愿登录确认（可选）。

---

## 5. 执行与回退

### 5.1 前置条件（全部满足才能正式迁移）

| 条件 | 说明 |
| --- | --- |
| Issue #20 合并 | 手机号登录、封禁用户不能登录（第 2.3、2.4 节） |
| AI 表的 Flyway 迁移合并 | 第 1.2 节；`account_unverified_contact`、`account_user_phone.verified_at` 的结构变更同时或另行新增版本化脚本（**不修改已应用的迁移**） |
| 迁移工具合并 | 含单元测试（向量、规范化、各类错误代码）与 Testcontainers 集成测试（用合成的 Node 形态与 Java 形态 SQLite 样本） |
| 至少一次完整演练 | 5.4 节，`ERROR` 为 0，`WARN` 已签注，记录耗时 |
| 第 7 节问题已决定 | 至少 Q-1 ～ Q-5 |
| 已提前 7 天通知用户 | FR-09：练习进度与历史不保留、`.qbank` 需重新导出、客户端需重新授权、需要重新登录 |

### 5.2 正式迁移步骤

```text
T-7 天  通知用户；在最新生产副本上完整演练一次（5.4）
T-0
  1. 旧站切换为维护页面，停止旧 Hub 与练习服务的写入（需要停机）
  2. 作者在旧服务器上制作停写快照（SQLite 在线备份 API，或停进程后复制 hub.sqlite 与 -wal 文件），
     检查 integrity_check，记录文件 SHA-256；快照另存一份作为回退备份
  3. 把快照副本放到执行迁移的机器的 local/ 下；迁移工具只读打开该副本
  4. 目标：新建的 V2 生产库，已执行全部 Flyway 迁移，账号与 AI 表为空
  5. inspect → 确认与演练结果一致（用户数、余额合计等）
  6. migrate（单个事务）→ 自动 verify
  7. 按 4.5 抽查登录与余额
  8. 通过：入口切换到 V2，开始观察窗口
     不通过：删除目标库后重建（或保持空库），撤销维护页面，旧站恢复服务
T+30 天 旧库快照只读保留至少 30 天（FR-09），之后归档
```

- **是否需要停机：** 需要。迁移数据量极小（约 17 个用户，几百行），迁移本身预计秒级；停机时间主要用于快照、校验与抽查，演练后给出准确时长。
- **谁操作服务器：** 快照与切换由作者执行。AI 编码助手只在本机处理 `local/` 中的副本，不连接任何服务器。

### 5.3 可重复执行（幂等）与失败回滚

- **单事务：** `migrate` 把所有写入放在**一个** MySQL 事务中（数据量小，可行）。任何一步失败（约束冲突、校验失败），事务整体回滚，目标库保持迁移前状态，不会出现半迁移。`verify` 在提交前于同一事务内运行一遍，不通过即回滚；提交后再独立运行一遍。
- **目标必须为空：** 开始前检查 `account_user`、`account_user_phone`、`account_block`、`account_unverified_contact`、`ai_account`、`ai_ledger`、`ai_redeem_code` 与两张归档表均为空，否则报 `ERROR TARGET_NOT_EMPTY` 并退出（防止迁入已有用户的生产库，或重复迁移）。
- **重复执行：** 已成功迁移的库再运行 `migrate` 会因目标非空而拒绝，不产生任何写入；需要重来时，删除并重建目标库后重跑（ADR-008：每次从空库开始，不做断点续迁）。`verify` 只读，可反复运行。
- **确定性：** 除 `created_at = 迁移时刻` 外，同一份源副本每次产生的目标数据完全相同（migration 流水 ID 由用户 ID 派生，`ref` 唯一），便于对比两次演练的结果。
- **源不变：** 只读打开副本，迁移前后副本 SHA-256 必须相同，写入报告。

### 5.4 在旧库副本上演练

1. 作者取得最新生产副本，放入 git 忽略的 `local/migration/source/`（不得提交、不得上传 CI）。
2. 用独立的 Compose 项目名启动一次性 MySQL（例如 `docker compose -p qf-migration-rehearsal …`，使用空闲端口），执行 V2 的全部 Flyway 迁移。
3. 依次运行 `inspect` → `migrate --dry-run` → `migrate` → `verify`，保存报告。
4. 在本机启动 V2（同一临时项目）连接该库，按 4.5 用测试账号登录核对余额（手机号登录在 #20 合并前标记为“阻塞”）。
5. **故障演练：**
   - 在副本的副本中人为制造一处不一致（例如改动某用户 `ai_points.balance`），确认报告出现 `BALANCE_INVARIANT_MISMATCH` 而不是静默通过；
   - 在目标库预先插入一个用户，确认报告 `TARGET_NOT_EMPTY` 且没有写入；
   - 在迁移中途注入失败（例如让最后一个用户的邮箱与已插入的冲突），确认事务回滚后目标库为空。
6. **回退演练：** 切到 V2 后再切回旧系统，确认旧系统可正常登录使用（旧副本未被修改）。
7. 记录各步骤耗时，作为正式停机时长的依据。结束后 `docker compose -p qf-migration-rehearsal down -v`（自己的临时项目）。

### 5.5 切换后的回退

- 切换后发现严重问题：入口切回旧系统，旧库保持停写前的状态，可直接恢复服务。
- **局限：** 切换后在 V2 中产生的数据不会回流到旧系统：新注册的账号、V2 中的 AI 扣费（用户受益，金额小）、**在 V2 中兑换的码**（旧库中仍是未兑换，回退后可能被再兑换一次）、在 V2 中修改的封禁状态。
- 因此切换后头几个小时为观察窗口。建议观察窗口内由管理员记录 V2 中的兑换与封禁操作；如需回退，回退前导出这些操作，回退后在旧系统中手工补做（是否在观察窗口内暂停兑换，见 Q-9）。是否需要“回流”工具，视演练结果决定（ADR-008）。

---

## 6. 发现的问题

以下是查阅旧源码和 V2 现有代码时发现的、需要知会或修正的问题（不含需要作者做产品决定的事项，那些在第 7 节）。

| 编号 | 问题 | 影响 | 建议 |
| --- | --- | --- | --- |
| I-1 | **FR-09 / 本任务说明与旧数据字典的提法冲突**：数据字典写“迁移后余额必须与流水总和一致”，但旧库中余额 = Σ流水 − Σ每日用量；需求 v1.11 已更正，数据字典未同步 | 实现者按数据字典会写出错误的校验 | 更新数据字典第 ① 节“管理与 AI”一行，指向本文 |
| I-2 | **“迁移全部流水”与“每用户写一条 migration 流水”同时要求**：若旧流水也写进 `ai_ledger`，V2 的 `余额 = Σ流水` 不成立 | 方案选择 | 本文采用归档表方案（3.1），待作者确认（Q-1） |
| I-3 | 旧数据字典、ADR-008 仍写“设备令牌迁移、客户端无需重新授权”（C-05 原文），与需求 v1.3“设备令牌不迁移”冲突；遗留契约 C-05 小节下“设备令牌只存哈希，可以直接迁移”同样过时 | 文档误导 | 按 v1.3 更正上述文档（ADR-008 已有修订说明，但正文和数据字典表格未改） |
| I-4 | 表名不一致：数据库设计第 4.5 节为 `ai_wallet`，设计 08 为 `ai_account` | 实现时混淆 | 统一为 `ai_account`，修改数据库设计第 4.5 节 |
| I-5 | V2 `AuthService.login` 不检查封禁，会为被封禁用户创建会话并返回成功（会话随后不可用） | 与“封禁用户不能登录”不符 | 已在 Issue #20 第 5 项，正式迁移前必须完成 |
| I-6 | 早期 Node 库可能没有 `official_ai_usage.points` 列（Java 启动时才补列，默认 0）。生产仍运行 Node，若生产库缺此列，旧余额无法用旧恒等式校验 | 校验可能无法完成 | `inspect` 先确认；缺列时报 `USAGE_POINTS_MISSING`，交作者决定（Q-3） |
| I-7 | 旧 Node Hub 源码已从旧仓库删除，Node 时代的扣费规则无法从源码核实，只能从数据推断 | 3.2 的差额无法从源码解释 | 演练时如出现差额，请作者回忆或从其他备份核实 |
| I-8 | V2 排序规则 `utf8mb4_0900_ai_ci` 不区分大小写与重音，旧 SQLite 区分 | 极端情况下邮箱唯一约束冲突 | `inspect` 预检（`EMAIL_COLLISION_CI`） |
| I-9 | 旧 `user_phones.verified` 保存验证时刻，V2 只有布尔值 | 验证时间丢失 | 新增 `verified_at`（Q-6） |
| I-10 | 数据库设计第 4.1 节提到 `account_unverified_contact` 等表“从略”，V1 迁移未创建 | 迁移无处写入 | 随 AI 表一起新增版本化脚本 |
| I-11 | 需求文档头部版本仍为 v1.10，变更记录中没有 v1.11 一行，但 FR-09 正文引用“v1.11 更正” | 版本记录不完整 | 补变更记录 |
| I-12 | 旧 `admin` 流水的 `ref` 存的是操作原因文本，不是 ID；旧 `signup` 流水的 `ref` 是固定文本 | 归档表 `ref` 需按文本处理 | 已在 1.2 中按 `VARCHAR(500)` 原样归档 |

---

## 7. 需要作者决定的事项

| 编号 | 问题 | 选项 | 建议 |
| --- | --- | --- | --- |
| Q-1 | 旧流水如何进入 V2 | A. 归档表 `ai_legacy_*` + 每用户一条 migration 流水（本文方案）；B. 旧流水写入 `ai_ledger`，每日用量拆成负数 `ai_call` 流水，不写 migration 流水；C. 旧流水写入 `ai_ledger`，再写一条冲正流水 = −Σ用量 | **A**：V2 账本干净，校验最简单；用户在 V2 的余额明细页可单独展示“旧平台记录” |
| Q-2 | 余额为 0（或从未有 AI 记录）的用户是否也写 migration 流水 | 写 `delta = 0` 一条 / 不写 | **写**，校验规则统一为“每用户恰好一条” |
| Q-3 | 旧余额与旧恒等式不一致（或缺 `points` 列无法校验）时以谁为准 | `ai_points.balance`（用户看到的余额）/ 恒等式计算值 / 逐个人工处理 | 以 **`ai_points.balance`** 为准，差额逐条列入报告由作者签注 |
| Q-4 | 兑换码是否迁移；未兑换的码上线后是否继续有效 | 全部迁移，可用的继续可用 / 只迁移已兑换的，未兑换码作废并由作者重新发放 / 不迁移 | **全部迁移，可用的继续有效**：持码人可能已付费；已兑换的码迁移后可防止重复识别 |
| Q-5 | 是否保留兑换码完整码的密文 `code_cipher` | 保留（V2 需要配置旧主密钥 `OFFICIAL_AI_MASTER_KEY`，并实现同样的 AES-256-GCM 布局：12 字节 IV + 16 字节 tag + 密文，AAD `quizforge-redeem-code-v1`）/ 置 NULL（管理员无法再查看未兑换码的完整码，只能看前缀） | 取决于作者是否还需要在后台复制未兑换的码；如果未兑换码都已发出，**置 NULL** 更简单，且 V2 不必持有旧主密钥 |
| Q-6 | 是否新增 `account_user_phone.verified_at` 保留手机号验证时间 | 新增 / 只保留布尔值 | **新增**（成本很小，避免丢弃数据） |
| Q-7 | V2 是否保留“新用户注册赠送 AI 余额”（旧 `ai_signup_bonus`） | 保留并沿用旧金额 / 取消 | 作者决定；迁移只在报告中记录旧值 |
| Q-8 | 注册 / 登录策略、邀请码在 V2 中是否保留 | 属于 L4 范围 | 不迁移数据；L4 设计时决定 |
| Q-9 | 切换后的观察窗口内，V2 是否暂停兑换码 | 暂停 / 不暂停，手工记录 | 观察窗口很短，建议**暂停**，避免回退后重复兑换 |
| Q-10 | 迁移工具放在哪里 | `backend` 中的独立 Maven 模块 / 独立目录 `tools/migration` | 与已有的 `tools/qbank-upgrade` 保持一致，由实现任务确定 |

---

## 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v0.1 | 2026-11-01 | 初稿：字段映射、V-2 核实结果、余额迁移方式、校验与报告、执行与回退、问题与待决定事项 |
