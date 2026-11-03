-- 复用练习任务的租约与限流，考试评分后自动归档。
ALTER TABLE practice_lifecycle_job MODIFY kind ENUM('finish','archive','exam_finish','exam_archive') NOT NULL;
ALTER TABLE exam_exam ADD COLUMN title VARCHAR(200) NOT NULL DEFAULT '';
UPDATE exam_exam e JOIN practice_collection c ON c.id=e.collection_id SET e.title=c.title;
ALTER TABLE exam_exam ADD KEY idx_exam_round (round_id, user_id);
