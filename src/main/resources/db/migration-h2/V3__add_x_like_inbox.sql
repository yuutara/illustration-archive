CREATE TABLE x_like_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    x_post_id VARCHAR(64) NOT NULL,
    x_author_id VARCHAR(64) NOT NULL,
    author_username VARCHAR(255) NOT NULL,
    author_display_name VARCHAR(255) NOT NULL,
    post_text VARCHAR(65535) NULL,
    post_created_at TIMESTAMP(3) NULL,
    status VARCHAR(32) NOT NULL,
    discovered_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT pk_x_like_item PRIMARY KEY (id),
    CONSTRAINT uk_x_like_item_post_id UNIQUE (x_post_id)
);
CREATE INDEX idx_x_like_inbox ON x_like_item (status, post_created_at DESC, id DESC);

CREATE TABLE x_like_media (
    id BIGINT NOT NULL AUTO_INCREMENT,
    x_like_item_id BIGINT NOT NULL,
    media_key VARCHAR(128) NOT NULL,
    sort_order INT NOT NULL,
    media_type VARCHAR(32) NOT NULL,
    source_url VARCHAR(65535) NULL,
    width INT NULL,
    height INT NULL,
    CONSTRAINT pk_x_like_media PRIMARY KEY (id),
    CONSTRAINT uk_x_like_media_key UNIQUE (x_like_item_id, media_key),
    CONSTRAINT uk_x_like_media_sort UNIQUE (x_like_item_id, sort_order),
    CONSTRAINT fk_x_like_media_item FOREIGN KEY (x_like_item_id) REFERENCES x_like_item (id) ON DELETE CASCADE
);
