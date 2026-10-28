-- content_* tables were created in V1; this migration only tightens identifier collations
-- and adds the extension review whitelist.

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
