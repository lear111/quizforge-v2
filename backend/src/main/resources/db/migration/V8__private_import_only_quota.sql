-- Product decision 2026-11-01: only privately imported banks consume storage quota.
-- Recompute from retained ledger items, preserving limits, daily traffic and business data.
DELETE FROM quota_storage_item WHERE category IN ('state', 'round', 'snapshot', 'resource', 'whiteboard', 'annotation');
UPDATE quota_account a
LEFT JOIN (SELECT user_id, SUM(size_bytes) AS used FROM quota_storage_item GROUP BY user_id) i ON i.user_id = a.user_id
SET a.storage_used = COALESCE(i.used, 0);

