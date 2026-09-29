ALTER TABLE x_like_item
    DROP INDEX idx_x_like_inbox,
    ADD KEY idx_x_like_inbox (status, discovered_at DESC, id DESC);
