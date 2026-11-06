-- 按北京时间保存逐日下载计数；既有累计下载数没有时间信息，不作历史倒推。
CREATE TABLE catalog_download_day (
    download_day DATE NOT NULL,
    pack_id CHAR(36) NOT NULL,
    downloads BIGINT NOT NULL,
    PRIMARY KEY (download_day, pack_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
