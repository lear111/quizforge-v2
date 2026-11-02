-- 站内通知中心（功能盘点 I 节）。
--
-- 发给所有人的通知只存一份（audience = all），不为每个用户复制；
-- 铃铛红点 = 是否存在比“用户上次打开通知界面的时间”更新、且对该用户可见的通知；
-- 单条已读用“用户 × 通知”的已读记录；题库通知（audience = bank）在读取时按用户是否拥有该题库筛选。

CREATE TABLE notification (
  id          CHAR(36)     NOT NULL,
  audience    VARCHAR(8)   NOT NULL,              -- all / user / bank
  target_id   VARCHAR(255) NOT NULL DEFAULT '',   -- all 为空串；user 为用户 ID；bank 为题库标识
  source      VARCHAR(16)  NOT NULL,              -- admin / system / publisher
  title       VARCHAR(100) NOT NULL,
  body        TEXT         NOT NULL,              -- 纯文本，应用层限制 5000 个字符
  pinned      BOOLEAN      NOT NULL DEFAULT FALSE,
  created_by  CHAR(36)     NULL,                  -- 发送的管理员或发布者；系统通知为空
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  -- 铃铛红点：(audience, target_id) 等值 + created_at 范围，直接判断“是否存在”。
  KEY idx_notification_audience (audience, target_id, created_at),
  KEY idx_notification_created (created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE notification_read (
  user_id          CHAR(36)    NOT NULL,
  notification_id  CHAR(36)    NOT NULL,
  read_at          DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id, notification_id),
  KEY idx_notification_read_notification (notification_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 用户上次打开通知界面的时间；没有行表示从未打开。
CREATE TABLE notification_seen (
  user_id  CHAR(36)    NOT NULL,
  seen_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
