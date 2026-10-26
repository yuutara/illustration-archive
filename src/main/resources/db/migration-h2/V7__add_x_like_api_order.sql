-- Mirrors db/migration/V7, which documents the meaning of each column.
ALTER TABLE x_like_item ADD COLUMN likes_order BIGINT NOT NULL DEFAULT 0;
DROP INDEX idx_x_like_inbox;
CREATE INDEX idx_x_like_inbox ON x_like_item (status, likes_order DESC, discovered_at DESC, id DESC);

ALTER TABLE x_like_sync_state ADD COLUMN anchor_ids VARCHAR(65535) NULL;
ALTER TABLE x_like_sync_state ADD COLUMN head_ids VARCHAR(65535) NULL;
ALTER TABLE x_like_sync_state ADD COLUMN matched_count INT NOT NULL DEFAULT 0;
ALTER TABLE x_like_sync_state ADD COLUMN order_next BIGINT NULL;
ALTER TABLE x_like_sync_state ADD COLUMN order_floor BIGINT NULL;
ALTER TABLE x_like_sync_state ADD COLUMN coverage_to_end BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE x_like_sync_seen_token ADD COLUMN sync_id TINYINT NOT NULL DEFAULT 1;
ALTER TABLE x_like_sync_seen_token DROP CONSTRAINT pk_x_like_sync_seen_token;
ALTER TABLE x_like_sync_seen_token ADD CONSTRAINT pk_x_like_sync_seen_token PRIMARY KEY (sync_id, token_sha256);
