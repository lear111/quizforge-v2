-- 锁定作答后等待浏览器；提交代次与评分代次共同阻止过期结果覆盖。
ALTER TABLE practice_question_state
  MODIFY grading_status ENUM('none','pending','graded','awaiting_browser') NOT NULL DEFAULT 'none',
  ADD COLUMN browser_report_sha256 BINARY(32) NULL;
-- 单题兜底沿用现有队列、租约与低优先级执行名额。
ALTER TABLE practice_lifecycle_job
  MODIFY kind ENUM('finish','archive','exam_finish','exam_archive','browser_grade') NOT NULL;
