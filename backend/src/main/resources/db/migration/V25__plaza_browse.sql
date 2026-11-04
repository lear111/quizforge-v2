-- 下载次数只做原子递增；收藏是用户书签，不复制题库内容。
ALTER TABLE catalog_pack ADD COLUMN download_count BIGINT NOT NULL DEFAULT 0,
 ADD KEY idx_plaza_downloads (visibility,status,download_count DESC,id DESC),
 ADD KEY idx_plaza_latest (visibility,status,created_at DESC,id DESC);
CREATE TABLE catalog_favorite (
 user_id CHAR(36) NOT NULL, pack_id CHAR(36) NOT NULL, created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(user_id,pack_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
