ALTER TABLE author ADD COLUMN x_user_id VARCHAR(64) NULL;
ALTER TABLE author ADD CONSTRAINT uk_author_x_user_id UNIQUE (x_user_id);
ALTER TABLE author DROP CONSTRAINT uk_author_x_username;
CREATE INDEX idx_author_x_username ON author (x_username);

ALTER TABLE x_like_item ADD COLUMN imported_illustration_id BIGINT NULL;
CREATE INDEX idx_x_like_imported_illustration ON x_like_item (imported_illustration_id);
ALTER TABLE x_like_item ADD CONSTRAINT fk_x_like_imported_illustration
    FOREIGN KEY (imported_illustration_id) REFERENCES illustration (id) ON DELETE SET NULL;
