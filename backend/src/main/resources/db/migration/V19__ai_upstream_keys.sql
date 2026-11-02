-- L5.2 模型接入：上游 API Key 池（设计 08 第 0 节，沿用旧平台规则）。
--
-- 最多 20 个 Key，按 position 顺序尝试，一次调用最多尝试 3 个。Key 用 QF_AI_MASTER_KEY 做 AES-256-GCM
-- 加密保存，只写不读：后台只显示末 4 位（key_hint）。单价以 0.0001 元为单位，表示每百万 token 的价格。
-- baseUrl 与模型 ID 由环境变量提供（全站一个上游），不存在这里。
--
-- state：ok 可用；unavailable 上游拒绝了 Key 本身（401/403 等），不再参与调用，管理员测试通过或重新启用后恢复。
-- enabled：管理员的启用 / 停用开关，与 state 独立。

CREATE TABLE ai_upstream_key (
  id              CHAR(36)      NOT NULL,
  seq             BIGINT        NOT NULL,           -- 管理界面显示的编号
  position        INT           NOT NULL,           -- 尝试顺序（小的先试）
  note            VARCHAR(60)   NOT NULL,
  key_cipher      VARCHAR(512)  NOT NULL,
  key_hint        CHAR(4)       NOT NULL,           -- Key 的末 4 位
  input_price     BIGINT        NOT NULL,           -- 每百万输入 token 的价格（0.0001 元）
  output_price    BIGINT        NOT NULL,           -- 每百万输出 token 的价格（0.0001 元）
  enabled         BOOLEAN       NOT NULL,
  expires_at      DATETIME(3)   NULL,
  state           VARCHAR(16)   NOT NULL,
  last_error      VARCHAR(200)  NULL,
  last_error_at   DATETIME(3)   NULL,
  calls           BIGINT        NOT NULL DEFAULT 0, -- 计费成功的调用次数
  input_tokens    BIGINT        NOT NULL DEFAULT 0,
  output_tokens   BIGINT        NOT NULL DEFAULT 0,
  spent           BIGINT        NOT NULL DEFAULT 0, -- 按本 Key 单价向用户收取的合计（0.0001 元）
  created_by      CHAR(36)      NOT NULL,
  created_at      DATETIME(3)   NOT NULL,
  updated_at      DATETIME(3)   NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_upstream_key_seq (seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
