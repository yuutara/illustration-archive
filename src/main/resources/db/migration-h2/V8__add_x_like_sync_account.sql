-- Mirrors db/migration/V8, which documents the meaning of each column.
CREATE TABLE x_like_sync_account (
    id TINYINT NOT NULL,
    owner_x_user_id VARCHAR(32) NULL,
    observed_x_user_id VARCHAR(32) NULL,
    observed_x_username VARCHAR(64) NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT pk_x_like_sync_account PRIMARY KEY (id)
);
