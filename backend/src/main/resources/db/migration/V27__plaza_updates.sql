-- 题库广场 3/4：版本自动切换、更新通知与“作者已移除”（设计 04 第 3 节）。
--
-- 版本切换与“作者已移除”使用已有的 practice_collection.source_revision / update_status，不新增练习表的列；
-- “作者已删除”在读取时由题库包状态推算。题库更新通知只存一份（notification.audience = 'bank'，target_id = 题库包 ID）。

-- 收藏者最后确认过的已审核版本：收藏列表据此显示“有新版本”。已有的收藏视为已看到当前版本。
ALTER TABLE catalog_favorite ADD COLUMN seen_revision INT NULL AFTER created_at;
UPDATE catalog_favorite f JOIN catalog_pack p ON p.id = f.pack_id SET f.seen_revision = p.approved_revision;

-- 题库更新通知改为按题库包定位（过去的接口按 bank.json 的 UUID，生产代码从未调用，没有需要迁移的数据）。
