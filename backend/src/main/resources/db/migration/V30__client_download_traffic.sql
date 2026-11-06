-- 客户端固定版本下载的匿名流量预算（遗留契约 C-05 第 11 节）：站点每月一行、每个 IP 每天一行。
-- 客户端下载不带账号，不能使用按用户计算的每日下载流量。IP 只存摘要。
CREATE TABLE client_download_traffic (
  period      VARCHAR(90)  NOT NULL,   -- month:2026-11 或 day:2026-11-05:<IP 的 SHA-256>
  bytes       BIGINT       NOT NULL,
  updated_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (period),
  KEY idx_client_traffic_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
