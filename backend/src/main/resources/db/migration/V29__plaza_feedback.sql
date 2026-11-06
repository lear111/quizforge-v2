-- 题库广场评论与举报；评论按纯文本展示，举报保留处理结果。
CREATE TABLE catalog_comment (
    id CHAR(36) NOT NULL,
    pack_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_comment_pack (pack_id, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE catalog_report (
    id CHAR(36) NOT NULL,
    pack_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    resolution VARCHAR(1000) NULL,
    reviewer_id CHAR(36) NULL,
    reviewed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    is_pending TINYINT GENERATED ALWAYS AS (status='pending') STORED,
    pending_user CHAR(36) GENERATED ALWAYS AS (CASE WHEN status='pending' THEN user_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_pending (pack_id, pending_user),
    KEY idx_report_queue (is_pending DESC, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 每个用户分别限频，原子 UPSERT 串行化同一窗口；过期行可按窗口清理。
CREATE TABLE catalog_feedback_rate (
    user_id CHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    window_until DATETIME(3) NOT NULL,
    attempts INT NOT NULL,
    PRIMARY KEY (user_id, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
