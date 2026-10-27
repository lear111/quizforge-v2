-- QuizForge V2 core schema.
-- Source: docs/design/01-database.md v0.4 (sections 4.1-4.4) and
--         docs/design/04-bank-sharing-and-updates.md (section 4).
-- Catalog, AI, admin and quota tables follow in later migrations with their modules.
--
-- Conventions (database design section 1):
--   * InnoDB, utf8mb4 / utf8mb4_0900_ai_ci, DATETIME(3) in UTC
--   * CHAR(36) UUID keys (legacy IDs kept; new rows use UUIDv7)
--   * BINARY(32) for SHA-256 values
--   * Every private table carries user_id
--   * No cross-module foreign keys (open item DB-7)

-- ---------------------------------------------------------------------------
-- identity: accounts and authentication (ADR-005)
-- ---------------------------------------------------------------------------

CREATE TABLE account_user (
  id              CHAR(36)      NOT NULL,
  email           VARCHAR(254)  NULL,
  name            VARCHAR(64)   NOT NULL,
  password_hash   VARCHAR(255)  NOT NULL,
  password_algo   VARCHAR(16)   NOT NULL,
  is_admin        BOOLEAN       NOT NULL DEFAULT FALSE,
  created_at      DATETIME(3)   NOT NULL,
  updated_at      DATETIME(3)   NOT NULL,
  version         BIGINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE account_user_phone (
  user_id   CHAR(36)    NOT NULL,
  phone     VARCHAR(32) NOT NULL,
  verified  BOOLEAN     NOT NULL,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE account_block (
  user_id     CHAR(36)     NOT NULL,
  actor_id    CHAR(36)     NOT NULL,
  reason      VARCHAR(500) NOT NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE auth_session (
  token_hash           BINARY(32)   NOT NULL,
  user_id              CHAR(36)     NOT NULL,
  created_at           DATETIME(3)  NOT NULL,
  last_used_at         DATETIME(3)  NOT NULL,
  idle_expires_at      DATETIME(3)  NOT NULL,
  absolute_expires_at  DATETIME(3)  NOT NULL,
  user_agent           VARCHAR(255) NULL,
  PRIMARY KEY (token_hash),
  KEY idx_session_user (user_id),
  KEY idx_session_expiry (absolute_expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE auth_device (
  id            CHAR(36)     NOT NULL,
  user_id       CHAR(36)     NOT NULL,
  token_hash    BINARY(32)   NOT NULL,
  name          VARCHAR(80)  NOT NULL,
  client        VARCHAR(40)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL,
  last_used_at  DATETIME(3)  NOT NULL,
  revoked_at    DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_token (token_hash),
  KEY idx_device_user (user_id, revoked_at, last_used_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE auth_connect_request (
  request_hash  BINARY(32)    NOT NULL,
  client        VARCHAR(40)   NOT NULL,
  redirect_uri  VARCHAR(200)  NOT NULL,
  state         VARCHAR(256)  NOT NULL,
  challenge     CHAR(43)      NOT NULL,
  device_name   VARCHAR(80)   NOT NULL,
  expires_at    DATETIME(3)   NOT NULL,
  PRIMARY KEY (request_hash),
  KEY idx_connect_request_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE auth_connect_code (
  code_hash     BINARY(32)   NOT NULL,
  user_id       CHAR(36)     NOT NULL,
  client        VARCHAR(40)  NOT NULL,
  redirect_uri  VARCHAR(200) NOT NULL,
  challenge     CHAR(43)     NOT NULL,
  device_name   VARCHAR(80)  NOT NULL,
  expires_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (code_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE auth_verification_code (
  channel           VARCHAR(16)  NOT NULL,
  subject_key       VARCHAR(128) NOT NULL,
  nonce             VARCHAR(64)  NOT NULL,
  digest            BINARY(32)   NOT NULL,
  expires_at        DATETIME(3)  NOT NULL,
  attempts          INT          NOT NULL DEFAULT 0,
  state             VARCHAR(16)  NOT NULL,
  captcha_verified  BOOLEAN      NOT NULL,
  PRIMARY KEY (channel, subject_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- content: immutable shared bank content and extension packages
-- ---------------------------------------------------------------------------

CREATE TABLE content_blob (
  sha256      BINARY(32)    NOT NULL,
  size        BIGINT        NOT NULL,
  mime        VARCHAR(100)  NOT NULL,
  created_at  DATETIME(3)   NOT NULL,
  PRIMARY KEY (sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE content_extension_package (
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

CREATE TABLE content_extension_file (
  package_digest  BINARY(32)    NOT NULL,
  path            VARCHAR(255)  NOT NULL,
  blob_sha256     BINARY(32)    NOT NULL,
  PRIMARY KEY (package_digest, path)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE content_bank (
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

CREATE TABLE content_question (
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

CREATE TABLE content_bank_asset (
  bank_id      CHAR(36)      NOT NULL,
  path         VARCHAR(255)  NOT NULL,
  blob_sha256  BINARY(32)    NOT NULL,
  PRIMARY KEY (bank_id, path),
  KEY idx_asset_blob (blob_sha256, bank_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- practice: user bank instances, progress, history
-- ---------------------------------------------------------------------------

CREATE TABLE practice_collection (
  id               CHAR(36)      NOT NULL,
  user_id          CHAR(36)      NOT NULL,
  bank_id          CHAR(36)      NOT NULL,
  title            VARCHAR(200)  NOT NULL,
  group_name       VARCHAR(200)  NULL,
  source           JSON          NOT NULL,
  legacy_route_id  VARCHAR(80)   NULL,
  source_pack_id   CHAR(36)      NULL,
  source_bank_key  VARCHAR(255)  NULL,
  source_revision  INT           NULL,
  update_status    ENUM('current', 'pending', 'removed') NOT NULL DEFAULT 'current',
  created_at       DATETIME(3)   NOT NULL,
  deleted_at       DATETIME(3)   NULL,
  PRIMARY KEY (id),
  KEY idx_collection_user (user_id, deleted_at, group_name),
  KEY idx_collection_bank (bank_id),
  UNIQUE KEY uk_collection_legacy (user_id, legacy_route_id),
  KEY idx_collection_source (source_pack_id, source_bank_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE practice_context (
  id                CHAR(36)     NOT NULL,
  user_id           CHAR(36)     NOT NULL,
  collection_id     CHAR(36)     NOT NULL,
  kind              ENUM('practice', 'exam') NOT NULL,
  scope_key         VARCHAR(64)  NOT NULL,
  progress_version  BIGINT       NOT NULL DEFAULT 0,
  current_round_id  CHAR(36)     NULL,
  created_at        DATETIME(3)  NOT NULL,
  updated_at        DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_context_scope (collection_id, kind, scope_key),
  KEY idx_context_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE practice_question_state (
  context_id             CHAR(36)       NOT NULL,
  question_id            VARCHAR(120)   NOT NULL,
  user_id                CHAR(36)       NOT NULL,
  status                 ENUM('unanswered', 'draft', 'submitted') NOT NULL,
  answer                 JSON           NULL,
  answer_state           JSON           NULL,
  result                 JSON           NULL,
  grading_status         ENUM('none', 'pending', 'graded') NOT NULL DEFAULT 'none',
  score                  DECIMAL(12, 4) NULL,
  max_score              DECIMAL(12, 4) NULL,
  outline_states         JSON           NULL,
  projection             JSON           NULL,
  revision               BIGINT         NOT NULL,
  submission_generation  INT            NOT NULL DEFAULT 0,
  grading_generation     INT            NOT NULL DEFAULT 0,
  content_sha256         BINARY(32)     NOT NULL,
  updated_at             DATETIME(3)    NOT NULL,
  PRIMARY KEY (context_id, question_id),
  KEY idx_state_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE practice_whiteboard (
  context_id   CHAR(36)      NOT NULL,
  question_id  VARCHAR(120)  NOT NULL,
  user_id      CHAR(36)      NOT NULL,
  draft        JSON          NOT NULL,
  size_bytes   INT           NOT NULL,
  updated_at   DATETIME(3)   NOT NULL,
  PRIMARY KEY (context_id, question_id),
  KEY idx_whiteboard_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE practice_round (
  id                   CHAR(36)     NOT NULL,
  user_id              CHAR(36)     NOT NULL,
  collection_id        CHAR(36)     NOT NULL,
  context_id           CHAR(36)     NOT NULL,
  mode                 ENUM('practice', 'exam') NOT NULL,
  status               ENUM('in_progress', 'completed', 'interrupted') NOT NULL,
  collection_snapshot  JSON         NOT NULL,
  summary              JSON         NULL,
  created_at           DATETIME(3)  NOT NULL,
  updated_at           DATETIME(3)  NOT NULL,
  completed_at         DATETIME(3)  NULL,
  deleted_at           DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_round_history (user_id, collection_id, deleted_at, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE practice_round_question (
  round_id          CHAR(36)      NOT NULL,
  question_id       VARCHAR(120)  NOT NULL,
  user_id           CHAR(36)      NOT NULL,
  payload           JSON          NOT NULL,
  extension_digest  BINARY(32)    NOT NULL,
  updated_at        DATETIME(3)   NOT NULL,
  PRIMARY KEY (round_id, question_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE practice_request_receipt (
  user_id         CHAR(36)     NOT NULL,
  request_id      VARCHAR(64)  NOT NULL,
  request_sha256  BINARY(32)   NOT NULL,
  response        JSON         NOT NULL,
  created_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (user_id, request_id),
  KEY idx_receipt_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE practice_user_resource (
  user_id      CHAR(36)     NOT NULL,
  blob_sha256  BINARY(32)   NOT NULL,
  mime         VARCHAR(50)  NOT NULL,
  size         INT          NOT NULL,
  created_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (user_id, blob_sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- exam
-- ---------------------------------------------------------------------------

CREATE TABLE exam_exam (
  id                 CHAR(36)     NOT NULL,
  user_id            CHAR(36)     NOT NULL,
  collection_id      CHAR(36)     NOT NULL,
  context_id         CHAR(36)     NOT NULL,
  bank_id            CHAR(36)     NOT NULL,
  status             ENUM('in_progress', 'grading', 'completed', 'deleted') NOT NULL,
  duration_minutes   INT          NULL,
  started_at         DATETIME(3)  NOT NULL,
  deadline_at        DATETIME(3)  NULL,
  submitted_at       DATETIME(3)  NULL,
  round_id           CHAR(36)     NULL,
  create_request_id  VARCHAR(64)  NOT NULL,
  version            BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam_context (context_id),
  UNIQUE KEY uk_exam_create (user_id, create_request_id),
  KEY idx_exam_user (user_id, collection_id, status),
  KEY idx_exam_deadline (status, deadline_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
