-- 题库广场 1/4：数据模型、发布与审核（设计 04 第 1、2、4、6 节，需求 FR-10 v1.13、FR-11）。
--
-- 广场信息（标题、简介、截图等）按版本保存：新版本审核期间广场继续显示上一个已审核版本的信息与内容，
-- 管理员审核的是这一版本的全部内容（题库、拓展、广场信息），通过后才对外可见。

-- 内容模块：题库身份与可重新打包的 bank.json。
--   bank_uuid       bank.json 中的 UUID（题库身份，设计 06 第 4 节）；它属于题库内容的一部分（参与内容哈希），
--                   所以按内容只存一份即可。
--   bank_json_sha256 规范化后的 bank.json（存入 content_blob）。从“我的题库”分享时据此与资源、拓展文件重新打包。
-- 本脚本之前导入的内容没有这两列：再次导入相同内容时补上；之前的私人题库需要重新导入后才能从“我的题库”分享。
ALTER TABLE content_bank
  ADD COLUMN bank_uuid CHAR(36) NULL AFTER content_sha256,
  ADD COLUMN bank_json_sha256 BINARY(32) NULL AFTER bank_uuid;

-- 练习模块：来源题库的身份由包内路径改为 bank.json 的 UUID（设计 04 第 4 节）。
-- 已有的值是路径（question-banks/<组>/<题库>/），不能换算成 UUID，只保留形如 UUID 的值。
ALTER TABLE practice_collection
  ADD COLUMN source_bank_uuid CHAR(36) NULL AFTER source_pack_id;
UPDATE practice_collection SET source_bank_uuid = LOWER(source_bank_key)
 WHERE source_bank_key REGEXP '^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$';
ALTER TABLE practice_collection
  DROP INDEX idx_collection_source,
  DROP COLUMN source_bank_key,
  ADD KEY idx_collection_source (source_pack_id, source_bank_uuid);

-- 撤销拓展前统计受影响的题库：按拓展摘要找题库内容。
ALTER TABLE content_question ADD KEY idx_question_extension (extension_digest, bank_id);

-- 拓展审核白名单：记录首次登记时间（导入时为未知摘要自动创建 pending 记录）。
ALTER TABLE admin_extension_review
  ADD COLUMN created_at DATETIME(3) NULL AFTER reason,
  ADD KEY idx_extension_review_status (status, created_at);

-- 题库包：只用于公开分享（私人导入不创建题库包）。
--   latest_revision    最新提交的版本号（1、2、3…）
--   approved_revision  最新的已审核版本；NULL 表示还没有通过审核的版本，广场不显示
--   status             active / deleted（发布者删除整个包：从广场下架，已加入的用户保留题库）
CREATE TABLE catalog_pack (
  id                 CHAR(36)     NOT NULL,
  owner_id           CHAR(36)     NOT NULL,
  latest_revision    INT          NOT NULL,
  approved_revision  INT          NULL,
  status             VARCHAR(16)  NOT NULL,
  created_at         DATETIME(3)  NOT NULL,
  updated_at         DATETIME(3)  NOT NULL,
  deleted_at         DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_catalog_pack_owner (owner_id, created_at),
  KEY idx_catalog_pack_public (status, approved_revision, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 每个提交的版本：原始 .qbank（逐字节保存，下载时原样返回）、广场信息与审核结果。每个版本只审核一次。
CREATE TABLE catalog_pack_revision (
  pack_id            CHAR(36)       NOT NULL,
  revision           INT            NOT NULL,
  title              VARCHAR(80)    NOT NULL,
  summary            VARCHAR(240)   NOT NULL,
  description        TEXT           NOT NULL,       -- 应用层限制 12000 个 UTF-16 单元（沿用旧版）
  category           VARCHAR(20)    NOT NULL,
  tags               JSON           NOT NULL,       -- 最多 5 个，合计不超过 100 字符
  release_notes      VARCHAR(2000)  NOT NULL,       -- 更新说明：第 1 版可为空串，以后随题库更新通知发送（PR ③）
  source             VARCHAR(16)    NOT NULL,       -- upload：作者上传的原文件；library：从“我的题库”打包一次
  filename           VARCHAR(255)   NOT NULL,
  blob_sha256        BINARY(32)     NOT NULL,       -- .qbank 文件（content_blob）
  size               BIGINT         NOT NULL,
  extension_digests  JSON           NOT NULL,       -- 该版本所用拓展的审核摘要（十六进制，排序）
  review_status      VARCHAR(16)    NOT NULL,       -- pending / approved / rejected
  reviewer_id        CHAR(36)       NULL,
  review_note        VARCHAR(500)   NULL,
  reviewed_at        DATETIME(3)    NULL,
  submitted_by       CHAR(36)       NOT NULL,
  submitted_at       DATETIME(3)    NOT NULL,
  PRIMARY KEY (pack_id, revision),
  KEY idx_catalog_revision_review (review_status, submitted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 某版本中的一本题库（设计 04 第 4 节）。新旧版本按 bank_uuid 对应。
CREATE TABLE catalog_pack_bank (
  pack_id    CHAR(36)      NOT NULL,
  revision   INT           NOT NULL,
  bank_uuid  CHAR(36)      NOT NULL,
  bank_id    CHAR(36)      NOT NULL,
  title      VARCHAR(300)  NOT NULL,
  position   INT           NOT NULL,
  PRIMARY KEY (pack_id, revision, bank_uuid),
  KEY idx_pack_bank_content (bank_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 截图等附件，按版本保存（新版本可以沿用上一版本的截图，指向同一份文件）。
CREATE TABLE catalog_pack_file (
  id           CHAR(36)      NOT NULL,
  pack_id      CHAR(36)      NOT NULL,
  revision     INT           NOT NULL,
  kind         VARCHAR(16)   NOT NULL,            -- screenshot
  position     INT           NOT NULL,
  name         VARCHAR(180)  NOT NULL,
  mime         VARCHAR(40)   NOT NULL,
  size         BIGINT        NOT NULL,
  blob_sha256  BINARY(32)    NOT NULL,
  PRIMARY KEY (id),
  KEY idx_catalog_file_revision (pack_id, revision, position)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 发布者操作与审核结果的记录（只插入）：发布、上传新版本、删除整个包、通过、拒绝。
-- 管理员操作另写 admin_audit；这里按题库包追溯完整经过，“我的发布”与审核页面都从这里读取。
CREATE TABLE catalog_pack_event (
  id          CHAR(36)     NOT NULL,
  pack_id     CHAR(36)     NOT NULL,
  revision    INT          NULL,
  actor_id    CHAR(36)     NOT NULL,
  action      VARCHAR(24)  NOT NULL,             -- published / revised / deleted / approved / rejected
  detail      JSON         NOT NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_catalog_event_pack (pack_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
