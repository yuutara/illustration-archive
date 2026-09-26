ALTER TABLE author
    ADD COLUMN x_user_id VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_author_x_user_id (x_user_id),
    DROP INDEX uk_author_x_username,
    ADD KEY idx_author_x_username (x_username);

ALTER TABLE x_like_item
    ADD COLUMN imported_illustration_id BIGINT NULL,
    ADD KEY idx_x_like_imported_illustration (imported_illustration_id),
    ADD CONSTRAINT fk_x_like_imported_illustration
        FOREIGN KEY (imported_illustration_id) REFERENCES illustration (id)
        ON DELETE SET NULL;
