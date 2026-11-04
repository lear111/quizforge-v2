-- 原始文件缺失与零字节文件不同；历史私人导入不能冒充可下载的原文件。
ALTER TABLE catalog_pack_revision MODIFY blob_sha256 BINARY(32) NULL;
ALTER TABLE practice_collection
  ADD COLUMN library_pack_id CHAR(36) NULL,
  ADD COLUMN library_revision INT NULL,
  ADD KEY idx_collection_library (user_id, library_pack_id, library_revision, deleted_at);

-- 资源和实例共同持有一次私人版本用量；所有计数变化都先锁 quota_account。
CREATE TABLE quota_private_revision (
  user_id CHAR(36) NOT NULL,
  pack_id CHAR(36) NOT NULL,
  revision INT NOT NULL,
  resource_present BOOLEAN NOT NULL,
  instance_count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, pack_id, revision),
  CHECK (instance_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO quota_private_revision (user_id, pack_id, revision, resource_present, instance_count)
SELECT p.owner_id, p.id, r.revision, p.status='active', 0
  FROM catalog_pack p JOIN catalog_pack_revision r ON r.pack_id=p.id
 WHERE p.visibility='private';

-- 每个旧实例补一个私人包，沿用实例 ID，使迁移可追溯且不改变现有练习与用量。
INSERT INTO catalog_pack (id, owner_id, latest_revision, approved_revision, status, created_at, updated_at, visibility)
SELECT c.id, c.user_id, 1, 1, 'active', c.created_at, c.created_at, 'private'
  FROM practice_collection c
 WHERE c.deleted_at IS NULL AND c.source_pack_id IS NULL
   AND JSON_UNQUOTE(JSON_EXTRACT(c.source,'$.type'))='upload';

INSERT INTO catalog_pack_revision
  (pack_id, revision, title, summary, description, category, tags, release_notes, source, filename,
   blob_sha256, size, extension_digests, review_status, submitted_by, submitted_at, validation_status)
SELECT c.id, 1, LEFT(c.title,80), '历史私人导入', '原文件不可用；保留既有题库内容和练习数据', '其他', JSON_ARRAY(), '',
       'legacy', LEFT(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.source,'$.filename')),'原文件不可用.qbank'),255),
       NULL, COALESCE(s.size_bytes,0),
       COALESCE((SELECT JSON_ARRAYAGG(LOWER(HEX(d.extension_digest))) FROM
         (SELECT DISTINCT bank_id,extension_digest FROM content_question) d WHERE d.bank_id=c.bank_id),JSON_ARRAY()),
       'approved', c.user_id, c.created_at, 'validated'
  FROM practice_collection c LEFT JOIN quota_storage_item s
    ON s.user_id=c.user_id AND s.category='import' AND s.object_id=c.id
 WHERE c.deleted_at IS NULL AND c.source_pack_id IS NULL
   AND JSON_UNQUOTE(JSON_EXTRACT(c.source,'$.type'))='upload';

INSERT INTO catalog_pack_bank (pack_id, revision, bank_uuid, bank_id, title, position)
SELECT c.id,1,COALESCE(b.bank_uuid,b.id),b.id,c.title,0
  FROM practice_collection c JOIN content_bank b ON b.id=c.bank_id
  JOIN catalog_pack_revision r ON r.pack_id=c.id AND r.source='legacy';

UPDATE practice_collection c JOIN catalog_pack_revision r ON r.pack_id=c.id AND r.source='legacy'
   SET c.library_pack_id=c.id,c.library_revision=1;

INSERT INTO quota_private_revision (user_id,pack_id,revision,resource_present,instance_count)
SELECT c.user_id,c.id,1,TRUE,1 FROM practice_collection c
  JOIN catalog_pack_revision r ON r.pack_id=c.id AND r.source='legacy';

-- 转移账目而不是重新收费；旧数据继续使用当时可恢复的逻辑大小。
UPDATE quota_storage_item s JOIN practice_collection c ON c.id=s.collection_id AND c.user_id=s.user_id
   SET s.category='private_pack',s.object_id=CONCAT(c.library_pack_id,':1'),s.collection_id=NULL
 WHERE s.category='import' AND c.library_pack_id IS NOT NULL;

ALTER TABLE content_upload_day ADD KEY idx_upload_day (upload_day, uploads DESC, user_id);
