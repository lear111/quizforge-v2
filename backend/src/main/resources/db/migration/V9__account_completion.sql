-- L4 账号补全：手机号验证时间、未验证联系方式、验证码发送记录、登录与注册设置、邀请码、账号审计。
-- 依据：docs/design/09-migration.md 第 1.1 节；旧平台 AUTH_POLICY.md、PHONE_AUTH.md、HUB_INVITATION_CLOUD.md。

-- 手机号的验证时刻（旧库 user_phones.verified 保存的是验证时刻）。verified 与 verified_at 保持一致。
ALTER TABLE account_user_phone
  ADD COLUMN verified_at DATETIME(3) NULL AFTER verified;

-- 注册不要求验证码时，新账号的联系方式记为“未验证”；之后通过验证码验证该联系方式时删除。
CREATE TABLE account_unverified_contact (
  user_id  CHAR(36)     NOT NULL,
  channel  VARCHAR(8)   NOT NULL,              -- email / phone
  contact  VARCHAR(254) NOT NULL,
  PRIMARY KEY (user_id, channel)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 验证码发送记录，只用于频率限制；地址与 IP 只存 HMAC 摘要。
CREATE TABLE auth_verification_send (
  id           CHAR(36)    NOT NULL,
  channel      VARCHAR(8)  NOT NULL,
  address_key  CHAR(64)    NOT NULL,
  ip_key       CHAR(64)    NOT NULL,
  created_at   DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_send_address (channel, address_key, created_at),
  KEY idx_send_ip (channel, ip_key, created_at),
  KEY idx_send_time (channel, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 每个通道一行，发送前加锁，使“检查频率 → 记录发送”在并发请求之间串行。
CREATE TABLE auth_verification_channel (
  channel  VARCHAR(8) NOT NULL,
  PRIMARY KEY (channel)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
INSERT INTO auth_verification_channel (channel) VALUES ('email'), ('phone');

-- 登录与注册设置，只有一行。revision 防止两个管理员互相覆盖。
-- 初始值全部关闭（与 M1 行为一致）；上线前由管理员按需开启（见 PR 说明中的待决定事项）。
CREATE TABLE auth_policy (
  id                     TINYINT     NOT NULL,
  login_code             BOOLEAN     NOT NULL,
  login_captcha          BOOLEAN     NOT NULL,
  registration_code      BOOLEAN     NOT NULL,
  registration_captcha   BOOLEAN     NOT NULL,
  invitation_required    BOOLEAN     NOT NULL,
  revision               BIGINT      NOT NULL,
  updated_at             DATETIME(3) NULL,
  PRIMARY KEY (id),
  CHECK (id = 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
INSERT INTO auth_policy VALUES (1, FALSE, FALSE, FALSE, FALSE, FALSE, 0, NULL);

-- 邀请码：只存完整码的 SHA-256 与识别前缀；完整码只在生成时返回一次。
CREATE TABLE account_invitation (
  id          CHAR(36)    NOT NULL,
  digest      BINARY(32)  NOT NULL,
  prefix      VARCHAR(16) NOT NULL,
  created_by  CHAR(36)    NOT NULL,
  created_at  DATETIME(3) NOT NULL,
  expires_at  DATETIME(3) NOT NULL,
  revoked_at  DATETIME(3) NULL,
  used_at     DATETIME(3) NULL,
  user_id     CHAR(36)    NULL,                -- 用此码注册的账号
  PRIMARY KEY (id),
  UNIQUE KEY uk_invitation_digest (digest),
  UNIQUE KEY uk_invitation_user (user_id),
  KEY idx_invitation_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 账号模块的管理审计：设置修改、封禁、角色、邀请码、故障恢复。actor_id 为空表示服务器命令行。
CREATE TABLE account_audit (
  id          CHAR(36)    NOT NULL,
  actor_id    CHAR(36)    NULL,
  target      VARCHAR(64) NOT NULL,
  action      VARCHAR(48) NOT NULL,
  detail      JSON        NOT NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_account_audit_time (created_at),
  KEY idx_account_audit_target (target, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
