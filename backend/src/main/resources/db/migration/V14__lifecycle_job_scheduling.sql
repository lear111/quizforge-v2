-- 已结束任务不进入领取范围；排队与过期租约按可领取时间交替推进。
ALTER TABLE practice_lifecycle_job
  ADD COLUMN archive_position INT NULL,
  ADD COLUMN available_at DATETIME(3) GENERATED ALWAYS AS (
    CASE WHEN status = 'queued' THEN updated_at
         WHEN status = 'running' THEN lease_until ELSE NULL END
  ) STORED,
  DROP INDEX idx_lifecycle_queue,
  ADD INDEX idx_lifecycle_available (available_at, id),
  ADD INDEX idx_lifecycle_cleanup (status, updated_at, id);

-- 升级时从已提交的单题快照恢复游标，不把 completed 当作连续的 position。
UPDATE practice_lifecycle_job j
SET archive_position = (
  SELECT MAX(q.position)
  FROM practice_round_question rq
  JOIN practice_collection c ON c.id = j.collection_id AND c.user_id = j.user_id
  JOIN content_question q ON q.bank_id = c.bank_id AND q.question_id = rq.question_id
  WHERE rq.round_id = j.round_id AND rq.user_id = j.user_id
)
WHERE j.kind = 'archive' AND j.status IN ('queued', 'running') AND j.completed > 0;
