-- Separate board revisions from answer revisions: high-frequency ink never updates answer state.
ALTER TABLE practice_whiteboard
  ADD COLUMN notes JSON NULL AFTER draft,
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 AFTER size_bytes;

