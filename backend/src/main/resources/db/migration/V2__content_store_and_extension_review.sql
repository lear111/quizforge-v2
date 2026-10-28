-- Content tables already ship in V1. Keep upgrade checksums and existing data intact.
-- IF NOT EXISTS also makes the content-store definitions explicit in this migration.
CREATE TABLE IF NOT EXISTS content_blob (
  sha256      BINARY(32)    NOT NULL,
  size        BIGINT        NOT NULL,
  mime        VARCHAR(100)  NOT NULL,
  created_at  DATETIME(3)   NOT NULL,
  PRIMARY KEY (sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS content_extension_package (
  digest         BINARY(32)    NOT NULL,
  extension_id   VARCHAR(120)  NOT NULL,
  version        VARCHAR(40)   NOT NULL,
  name           VARCHAR(200)  NOT NULL,
  manifest       JSON          NOT NULL,
  api_major      INT           NOT NULL,
  api_min_minor  INT           NOT NULL,
  created_at     DATETIME(3)   NOT NULL,
  PRIMARY KEY (digest)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS content_extension_file (
  package_digest  BINARY(32)    NOT NULL,
  path            VARCHAR(255)  NOT NULL,
  blob_sha256     BINARY(32)    NOT NULL,
  PRIMARY KEY (package_digest, path)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS content_bank (
  id              CHAR(36)      NOT NULL,
  content_sha256  BINARY(32)    NOT NULL,
  source_title    VARCHAR(300)  NOT NULL,
  description     TEXT          NULL,
  format_version  INT           NOT NULL,
  features        JSON          NOT NULL,
  listening       JSON          NULL,
  question_count  INT           NOT NULL,
  created_at      DATETIME(3)   NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bank_content (content_sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS content_question (
  bank_id           CHAR(36)       NOT NULL,
  question_id       VARCHAR(120)   NOT NULL,
  position          INT            NOT NULL,
  title             VARCHAR(300)   NOT NULL,
  extension_digest  BINARY(32)     NOT NULL,
  outline           JSON           NULL,
  listening         JSON           NULL,
  data              JSON           NOT NULL,
  max_score         DECIMAL(12, 4) NOT NULL,
  content_sha256    BINARY(32)     NOT NULL,
  PRIMARY KEY (bank_id, question_id),
  UNIQUE KEY uk_question_position (bank_id, position)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS content_bank_asset (
  bank_id      CHAR(36)      NOT NULL,
  path         VARCHAR(255)  NOT NULL,
  blob_sha256  BINARY(32)    NOT NULL,
  PRIMARY KEY (bank_id, path),
  KEY idx_asset_blob (blob_sha256, bank_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- These identifiers are case-sensitive legacy file/question identities. Keep the
-- table defaults, but do not fold distinct names or Unicode normalization forms.
ALTER TABLE content_extension_file MODIFY path VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL;
ALTER TABLE content_bank_asset MODIFY path VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL;
ALTER TABLE content_question MODIFY question_id VARCHAR(120) COLLATE utf8mb4_0900_bin NOT NULL;

CREATE TABLE admin_extension_review (
  digest BINARY(32) NOT NULL,
  extension_id VARCHAR(120) NOT NULL,
  version VARCHAR(40) NOT NULL,
  status ENUM('approved', 'revoked', 'pending', 'rejected') NOT NULL DEFAULT 'pending',
  reviewer VARCHAR(120) NULL,
  reviewed_at DATETIME(3) NULL,
  reason VARCHAR(500) NULL,
  PRIMARY KEY (digest)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
