-- 目录版本用于跨标签页的条件更新；分组单独保存以支持空文件夹。
CREATE TABLE practice_directory_revision (
    user_id CHAR(36) NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE practice_group (
    user_id CHAR(36) NOT NULL,
    name VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    PRIMARY KEY (user_id, name)
) ENGINE=InnoDB;

INSERT INTO practice_directory_revision (user_id) SELECT DISTINCT user_id FROM practice_collection;
INSERT IGNORE INTO practice_group (user_id, name)
SELECT DISTINCT user_id, group_name FROM practice_collection
WHERE deleted_at IS NULL AND group_name IS NOT NULL AND CHAR_LENGTH(group_name) BETWEEN 1 AND 120;
