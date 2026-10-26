DROP INDEX idx_x_like_inbox;
CREATE INDEX idx_x_like_inbox ON x_like_item (status, discovered_at DESC, id DESC);
