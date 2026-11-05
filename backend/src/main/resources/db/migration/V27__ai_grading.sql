-- 任务、目标绑定和用户偏好分开保存；模型正文只存在服务器任务表中。
CREATE TABLE ai_grading_task (
  id CHAR(36) NOT NULL PRIMARY KEY,
  user_id CHAR(36) NOT NULL,
  kind ENUM('bank','exam') NOT NULL,
  collection_id CHAR(36) NOT NULL,
  context_id CHAR(36) NOT NULL,
  question_id VARCHAR(120) COLLATE utf8mb4_bin NOT NULL,
  target_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
  round_id CHAR(36) NOT NULL,
  submission_generation INT NOT NULL,
  grading_generation INT NOT NULL,
  answer_sha256 BINARY(32) NOT NULL,
  content_sha256 BINARY(32) NOT NULL,
  status ENUM('queued','running','model_returned','succeeded','failed','superseded','confirmed') NOT NULL,
  priority SMALLINT NOT NULL,
  automatic BOOLEAN NOT NULL DEFAULT FALSE,
  attempts INT NOT NULL DEFAULT 0,
  model_calls INT NOT NULL DEFAULT 0,
  repair_used BOOLEAN NOT NULL DEFAULT FALSE,
  next_attempt_at DATETIME(3) NOT NULL,
  locked_until DATETIME(3) NULL,
  input_json JSON NULL,
  checkpoint JSON NULL,
  candidate JSON NULL,
  candidate_version CHAR(36) NULL,
  cost BIGINT NOT NULL DEFAULT 0,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_ai_grading_queue(status,priority,created_at,id),
  KEY idx_ai_grading_lease(status,locked_until),
  KEY idx_ai_grading_scope(user_id,context_id,question_id,target_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- task_id 本身就是某个目标的只增换代标记；小题采用不会换掉兄弟目标的绑定。
CREATE TABLE ai_grading_binding (
  context_id CHAR(36) NOT NULL,
  question_id VARCHAR(120) COLLATE utf8mb4_bin NOT NULL,
  target_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  task_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  PRIMARY KEY(context_id,question_id,target_id),
  KEY idx_ai_grading_binding_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_grading_setting (
  user_id CHAR(36) NOT NULL PRIMARY KEY,
  auto_grade BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
