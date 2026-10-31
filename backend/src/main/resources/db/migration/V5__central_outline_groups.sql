-- NULL preserves existing imported questions without author-defined groups.
ALTER TABLE content_question
    ADD COLUMN outline_group_index INT NULL,
    ADD COLUMN outline_group_name VARCHAR(80) NULL;
