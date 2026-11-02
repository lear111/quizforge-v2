-- 在线活跃统计（功能盘点 G 节，参考旧平台 /api/activity）。
-- 只保存“某天有哪些用户来过、是否练习过”；心跳与“当前在线”只在内存中计数，不写数据库。
-- 应用每分钟把内存中的当天活跃用户批量写入本表（单实例，见 ActivityTracker）。
CREATE TABLE activity_daily (
  day        DATE     NOT NULL,          -- 北京时间的日期
  user_id    CHAR(36) NOT NULL,
  practiced  BOOLEAN  NOT NULL DEFAULT FALSE,
  PRIMARY KEY (day, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
