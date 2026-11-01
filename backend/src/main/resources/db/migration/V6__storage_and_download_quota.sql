CREATE TABLE quota_defaults (
  id TINYINT NOT NULL PRIMARY KEY,
  storage_bytes BIGINT NOT NULL,
  daily_download_bytes BIGINT NOT NULL,
  CHECK (storage_bytes >= 0 AND daily_download_bytes >= 0)
) ENGINE=InnoDB;
INSERT INTO quota_defaults VALUES (1, 268435456, 268435456);

CREATE TABLE quota_account (
  user_id CHAR(36) NOT NULL PRIMARY KEY,
  storage_limit BIGINT NULL,
  daily_download_limit BIGINT NULL,
  storage_used BIGINT NOT NULL DEFAULT 0,
  download_day DATE NULL,
  download_used BIGINT NOT NULL DEFAULT 0,
  CHECK ((storage_limit IS NULL OR storage_limit >= 0) AND
         (daily_download_limit IS NULL OR daily_download_limit >= 0) AND
         storage_used >= 0 AND download_used >= 0)
) ENGINE=InnoDB;

-- Logical ownership is independent of the global content/blob deduplication keys.
CREATE TABLE quota_storage_item (
  user_id CHAR(36) NOT NULL,
  category VARCHAR(24) NOT NULL,
  object_id VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
  collection_id CHAR(36) NULL,
  size_bytes BIGINT NOT NULL,
  PRIMARY KEY (user_id, category, object_id),
  KEY idx_quota_collection (user_id, collection_id),
  CHECK (size_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE quota_adjustment (
  id CHAR(36) NOT NULL PRIMARY KEY,
  actor_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  previous_limits JSON NOT NULL,
  new_limits JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY idx_quota_adjustment_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Pre-quota imports have no original bank.json byte length. Product-approved backfill:
-- count recoverable canonical JSON and referenced logical files per private instance.
INSERT INTO quota_storage_item (user_id, category, object_id, collection_id, size_bytes)
SELECT c.user_id, 'import', c.id, c.id,
  OCTET_LENGTH(JSON_OBJECT('title', b.source_title, 'description', b.description,
    'formatVersion', b.format_version, 'features', b.features, 'listening', b.listening)) +
  COALESCE((SELECT SUM(OCTET_LENGTH(JSON_OBJECT('id', q.question_id, 'title', q.title,
    'data', q.data, 'outline', q.outline, 'listening', q.listening, 'extension', HEX(q.extension_digest))))
    FROM content_question q WHERE q.bank_id = b.id), 0) +
  COALESCE((SELECT SUM(bl.size) FROM content_bank_asset a JOIN content_blob bl ON bl.sha256 = a.blob_sha256
    WHERE a.bank_id = b.id), 0) +
  COALESCE((SELECT SUM(bl.size) FROM content_extension_file f JOIN content_blob bl ON bl.sha256 = f.blob_sha256
    WHERE f.package_digest IN (SELECT q.extension_digest FROM content_question q WHERE q.bank_id = b.id)), 0)
FROM practice_collection c JOIN content_bank b ON b.id = c.bank_id
WHERE c.deleted_at IS NULL AND c.source_pack_id IS NULL AND JSON_UNQUOTE(JSON_EXTRACT(c.source, '$.type')) = 'upload';

INSERT INTO quota_storage_item (user_id, category, object_id, collection_id, size_bytes)
SELECT s.user_id, 'state', CONCAT(s.context_id, ':', s.question_id), c.collection_id,
  COALESCE(OCTET_LENGTH(CAST(s.answer AS CHAR CHARACTER SET utf8mb4)),0) +
  COALESCE(OCTET_LENGTH(CAST(s.answer_state AS CHAR CHARACTER SET utf8mb4)),0) +
  COALESCE(OCTET_LENGTH(CAST(s.result AS CHAR CHARACTER SET utf8mb4)),0) +
  COALESCE(OCTET_LENGTH(CAST(s.outline_states AS CHAR CHARACTER SET utf8mb4)),0) +
  COALESCE(OCTET_LENGTH(CAST(s.projection AS CHAR CHARACTER SET utf8mb4)),0)
FROM practice_question_state s JOIN practice_context c ON c.id = s.context_id AND c.user_id = s.user_id
JOIN practice_collection p ON p.id = c.collection_id AND p.user_id = c.user_id WHERE p.deleted_at IS NULL;

INSERT INTO quota_storage_item (user_id, category, object_id, collection_id, size_bytes)
SELECT w.user_id, 'whiteboard', CONCAT(w.context_id, ':', w.question_id), c.collection_id,
  OCTET_LENGTH(CAST(w.draft AS CHAR CHARACTER SET utf8mb4))
FROM practice_whiteboard w JOIN practice_context c ON c.id = w.context_id AND c.user_id = w.user_id
JOIN practice_collection p ON p.id = c.collection_id AND p.user_id = c.user_id WHERE p.deleted_at IS NULL;

INSERT INTO quota_storage_item (user_id, category, object_id, collection_id, size_bytes)
SELECT r.user_id, 'round', r.id, r.collection_id,
  OCTET_LENGTH(CAST(r.collection_snapshot AS CHAR CHARACTER SET utf8mb4)) +
  COALESCE(OCTET_LENGTH(CAST(r.summary AS CHAR CHARACTER SET utf8mb4)),0)
FROM practice_round r JOIN practice_collection p ON p.id = r.collection_id AND p.user_id = r.user_id
WHERE r.deleted_at IS NULL AND p.deleted_at IS NULL;

INSERT INTO quota_storage_item (user_id, category, object_id, collection_id, size_bytes)
SELECT q.user_id, 'snapshot', CONCAT(q.round_id, ':', q.question_id), r.collection_id,
  OCTET_LENGTH(CAST(q.payload AS CHAR CHARACTER SET utf8mb4))
FROM practice_round_question q JOIN practice_round r ON r.id = q.round_id AND r.user_id = q.user_id
JOIN practice_collection p ON p.id = r.collection_id AND p.user_id = r.user_id
WHERE r.deleted_at IS NULL AND p.deleted_at IS NULL;

INSERT INTO quota_storage_item (user_id, category, object_id, collection_id, size_bytes)
SELECT user_id, 'resource', HEX(blob_sha256), NULL, size FROM practice_user_resource;

INSERT INTO quota_account (user_id, storage_used)
SELECT u.id, COALESCE(SUM(i.size_bytes),0) FROM account_user u
LEFT JOIN quota_storage_item i ON i.user_id = u.id GROUP BY u.id;
