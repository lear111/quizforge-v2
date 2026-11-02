-- 管理后台（PR 1）：统一的管理员操作审计，以及用户列表的键集分页索引。
--
-- 为什么不继续用 account_audit：表名前缀决定所属模块（数据库设计第 1 节），account_audit 属于 identity 模块；
-- 配额调整（quota 模块）以及以后的题库与拓展审核（catalog / admin 模块）都要写审计，若都写进 account_audit
-- 就违反“一个模块只读写自己的表”。因此审计改由 admin 模块的 admin_audit 统一保存，各模块通过
-- AdminAuditService 写入；module 列标明来源。account_audit 的已有记录原样迁入后删除该表。

CREATE TABLE admin_audit (
  id          CHAR(36)    NOT NULL,
  actor_id    CHAR(36)    NULL,                 -- 为空表示服务器命令行
  module      VARCHAR(24) NOT NULL,             -- account / quota / …
  action      VARCHAR(48) NOT NULL,
  target      VARCHAR(64) NOT NULL,
  detail      JSON        NOT NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_admin_audit_time (created_at, id),
  KEY idx_admin_audit_target (target, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO admin_audit (id, actor_id, module, action, target, detail, created_at)
SELECT id, actor_id, 'account', action, target, detail, created_at FROM account_audit;

DROP TABLE account_audit;

-- 用户列表按注册时间倒序、键集分页（created_at, id）。
ALTER TABLE account_user ADD KEY idx_user_created (created_at, id);
