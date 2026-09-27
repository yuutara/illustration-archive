CREATE TABLE x_like_sync_state (
    id TINYINT NOT NULL,
    next_token TEXT NULL,
    max_results INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE x_like_sync_seen_token (
    token_sha256 BINARY(32) NOT NULL,
    PRIMARY KEY (token_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
