-- L5.1 AI 余额、流水与兑换码（设计 08 第 1 节）。金额单位 0.0001 元（10000 = 1 元），BIGINT。
--
-- ai_account：每用户一行，当前余额的唯一来源；所有变动先 SELECT … FOR UPDATE 锁住这一行。
--             行在注册时创建（本脚本为已有用户补建），因此加锁读的永远是已存在的行，不会产生间隙锁。
-- ai_ledger ：只插入的流水。seq 为该用户的流水序号（1、2、3…，在余额行锁内分配），
--             balance_after = 上一笔的 balance_after + delta，对账按 seq 检查连续性。
--             唯一约束 (kind, ref)：同一来源只能入账一次。

CREATE TABLE ai_account (
  user_id     CHAR(36)    NOT NULL,
  balance     BIGINT      NOT NULL,              -- 可以为负（欠费）
  ledger_seq  BIGINT      NOT NULL,              -- 最后一笔流水的 seq
  updated_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ai_ledger (
  id             CHAR(36)     NOT NULL,
  user_id        CHAR(36)     NOT NULL,
  seq            BIGINT       NOT NULL,
  delta          BIGINT       NOT NULL,           -- 正为增加
  balance_after  BIGINT       NOT NULL,
  kind           VARCHAR(16)  NOT NULL,           -- default_credit / redeem / ai_call / admin / migration / refund
  ref            VARCHAR(100) NOT NULL,           -- 来源：用户 ID、兑换码记录 ID、AI 任务 ID、管理员操作 ID…
  actor_id       CHAR(36)     NULL,               -- 管理员调整的操作人
  reason         VARCHAR(200) NULL,               -- 管理员调整的原因
  created_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_ledger_source (kind, ref),
  UNIQUE KEY uk_ai_ledger_user_seq (user_id, seq),
  KEY idx_ai_ledger_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 兑换码：库中只存 SHA-256（规范化后的 16 位码体）与识别前缀；完整码用 AES-256-GCM 加密保存。
CREATE TABLE ai_redeem_code (
  id           CHAR(36)     NOT NULL,
  seq          BIGINT       NOT NULL,           -- 管理界面显示的编号
  digest       BINARY(32)   NOT NULL,
  prefix       VARCHAR(8)   NOT NULL,           -- 'AI-XXXX'
  points       BIGINT       NOT NULL,
  note         VARCHAR(60)  NOT NULL,
  created_by   CHAR(36)     NOT NULL,
  created_at   DATETIME(3)  NOT NULL,
  expires_at   DATETIME(3)  NOT NULL,
  revoked_at   DATETIME(3)  NULL,
  redeemed_at  DATETIME(3)  NULL,
  redeemed_by  CHAR(36)     NULL,
  code_cipher  VARCHAR(255) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_redeem_digest (digest),
  UNIQUE KEY uk_ai_redeem_seq (seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 已有用户补建余额行，并发放默认 1 元（需求 FR-09 v1.12：新老用户默认都有 1 元）。
-- 默认额度的流水 kind = default_credit、ref = 用户 ID：同一用户只能发放一次。
INSERT INTO ai_account (user_id, balance, ledger_seq, updated_at)
SELECT id, 10000, 1, UTC_TIMESTAMP(3) FROM account_user;

INSERT INTO ai_ledger (id, user_id, seq, delta, balance_after, kind, ref, created_at)
SELECT UUID(), id, 1, 10000, 10000, 'default_credit', id, UTC_TIMESTAMP(3) FROM account_user;
