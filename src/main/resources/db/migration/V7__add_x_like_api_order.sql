-- Zero keeps the original discovery-time fallback for every existing row.
-- Positive ranks are recent API observations; negative ranks are historical observations.
ALTER TABLE x_like_item
    ADD COLUMN likes_order BIGINT NOT NULL DEFAULT 0,
    DROP INDEX idx_x_like_inbox,
    ADD KEY idx_x_like_inbox (status, likes_order DESC, discovered_at DESC, id DESC);

-- id=1 remains History; id=2 is the resumable Recent sweep. Old rows stay intact.
ALTER TABLE x_like_sync_state
    ADD COLUMN anchor_ids TEXT NULL,
    ADD COLUMN head_ids TEXT NULL,
    ADD COLUMN matched_count INT NOT NULL DEFAULT 0,
    ADD COLUMN order_next BIGINT NULL,
    ADD COLUMN order_floor BIGINT NULL,
    ADD COLUMN coverage_to_end BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE x_like_sync_seen_token
    ADD COLUMN sync_id TINYINT NOT NULL DEFAULT 1,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (sync_id, token_sha256);
