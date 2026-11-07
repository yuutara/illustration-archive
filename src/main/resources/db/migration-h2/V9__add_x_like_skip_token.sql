-- Mirrors db/migration/V9, which documents the column.
ALTER TABLE x_like_item ADD COLUMN skip_token CHAR(36) NULL;
