-- 完成练习不生成历史；只有再练一次才归档本轮状态。
ALTER TABLE practice_context
  ADD COLUMN completion_status ENUM('active','finishing','completed','archiving') NOT NULL DEFAULT 'active',
  ADD COLUMN lifecycle_job_id CHAR(36) NULL;
ALTER TABLE practice_round ADD COLUMN history_frozen_at DATETIME(3) NULL;
ALTER TABLE practice_question_state
  ADD COLUMN attempt_count INT NULL DEFAULT 0,
  ADD COLUMN first_correct BOOLEAN NULL,
  ADD COLUMN grading_source VARCHAR(12) NULL;
-- 旧 generation 也累加重做，无法据此精确还原全部提交次数。
UPDATE practice_question_state SET attempt_count = CASE WHEN submission_generation = 0 THEN 0
    WHEN submission_generation = 1 AND status = 'submitted' THEN 1 ELSE NULL END,
  first_correct = CASE WHEN submission_generation = 1 AND status = 'submitted' AND grading_generation = 0 AND grading_status = 'graded' THEN score = max_score ELSE NULL END,
  grading_source = CASE WHEN status = 'submitted' AND grading_generation = 0 THEN 'rule' ELSE NULL END;
UPDATE practice_context c JOIN practice_round r ON r.id = c.current_round_id
  SET c.completion_status = 'completed' WHERE r.status = 'completed';
-- 旧历史可能只有已尝试题；按旧版本保留，不虚构已丢失的状态。
UPDATE practice_round r LEFT JOIN practice_context c ON c.current_round_id = r.id
  SET r.history_frozen_at = COALESCE(r.completed_at,r.updated_at)
  WHERE r.status = 'completed' AND c.id IS NULL;
CREATE TABLE practice_lifecycle_job (
  id CHAR(36) NOT NULL PRIMARY KEY, user_id CHAR(36) NOT NULL, collection_id CHAR(36) NOT NULL,
  context_id CHAR(36) NOT NULL, round_id CHAR(36) NOT NULL,
  kind ENUM('finish','archive') NOT NULL, status ENUM('queued','running','completed','failed') NOT NULL,
  total INT NOT NULL, completed INT NOT NULL DEFAULT 0,
  lease_owner CHAR(36) NULL, lease_until DATETIME(3) NULL,
  result JSON NULL, error_code VARCHAR(64) NULL, error_message VARCHAR(300) NULL,
  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
  KEY idx_lifecycle_queue(status,lease_until,created_at), KEY idx_lifecycle_collection(user_id,collection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE practice_history_annotation (
  history_id CHAR(36) NOT NULL, question_id VARCHAR(120) NOT NULL, user_id CHAR(36) NOT NULL,
  notes JSON NOT NULL, revision BIGINT NOT NULL, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(history_id,question_id), KEY idx_history_annotation_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE practice_history_asset (
  history_id CHAR(36) NOT NULL, blob_sha256 BINARY(32) NOT NULL, path VARCHAR(255) NOT NULL,
  PRIMARY KEY(history_id,path), KEY idx_history_asset_sha(blob_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
