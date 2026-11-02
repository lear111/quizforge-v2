-- 目录版本用于跨标签页的条件更新；分组单独保存以支持空文件夹。
CREATE TABLE library_catalog (
    user_id CHAR(36) NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE library_group (
    user_id CHAR(36) NOT NULL,
    name VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    PRIMARY KEY (user_id, name)
) ENGINE=InnoDB;

INSERT INTO library_catalog (user_id) SELECT DISTINCT user_id FROM practice_collection;
INSERT IGNORE INTO library_group (user_id, name)
SELECT DISTINCT user_id, group_name FROM practice_collection
WHERE deleted_at IS NULL AND group_name IS NOT NULL AND CHAR_LENGTH(group_name) BETWEEN 1 AND 120;

-- 保留作者文档中的 UUID、未引用题目以及作者原值，供无损导出使用。
CREATE TABLE content_bank_document (
    bank_id CHAR(36) NOT NULL PRIMARY KEY,
    author_json JSON NOT NULL,
    author_bindings JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
