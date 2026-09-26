CREATE TABLE x_like_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    x_post_id VARCHAR(64) NOT NULL,
    x_author_id VARCHAR(64) NOT NULL,
    author_username VARCHAR(255) NOT NULL,
    author_display_name VARCHAR(255) NOT NULL,
    post_text TEXT NULL,
    post_created_at DATETIME(3) NULL,
    status VARCHAR(32) NOT NULL,
    discovered_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_x_like_item_post_id (x_post_id),
    KEY idx_x_like_inbox (status, post_created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE x_like_media (
    id BIGINT NOT NULL AUTO_INCREMENT,
    x_like_item_id BIGINT NOT NULL,
    media_key VARCHAR(128) NOT NULL,
    sort_order INT NOT NULL,
    media_type VARCHAR(32) NOT NULL,
    source_url TEXT NULL,
    width INT NULL,
    height INT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_x_like_media_key (x_like_item_id, media_key),
    UNIQUE KEY uk_x_like_media_sort (x_like_item_id, sort_order),
    CONSTRAINT fk_x_like_media_item FOREIGN KEY (x_like_item_id)
        REFERENCES x_like_item (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
