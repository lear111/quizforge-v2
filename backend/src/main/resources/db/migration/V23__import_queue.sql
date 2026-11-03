-- 原文件先入库，内容校验由持久化任务异步完成；既有公开版本视为已经完成内容校验。
ALTER TABLE catalog_pack ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'public';
-- 自动校验是系统动作，没有登录操作者。
ALTER TABLE catalog_pack_event MODIFY actor_id CHAR(36) NULL;
ALTER TABLE catalog_pack_revision
    ADD COLUMN validation_status VARCHAR(24) NOT NULL DEFAULT 'validated',
    ADD COLUMN validation_reason VARCHAR(1000) NULL,
    ADD COLUMN notified_status VARCHAR(16) NULL;

CREATE TABLE content_import_job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    pack_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'queued',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(3) NOT NULL,
    lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(64) NULL,
    started_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_import_revision (pack_id, revision),
    KEY idx_import_ready (status, available_at, created_at, id),
    KEY idx_import_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 固定槽位锁保证多实例合计并发，不依赖某一 JVM 的信号量。
CREATE TABLE content_import_slot (
    id INT PRIMARY KEY,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE content_upload_day (
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    upload_day DATE NOT NULL,
    uploads INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, upload_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 仅存服务器执行规则后得到的结果，不接受客户端结果。
CREATE TABLE content_validation_cache (
    extension_digest BINARY(32) NOT NULL,
    input_sha256 BINARY(32) NOT NULL,
    outcome JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (extension_digest, input_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
