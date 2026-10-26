CREATE TABLE x_like_sync_state (
    id TINYINT NOT NULL,
    next_token VARCHAR(65535) NULL,
    max_results INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT pk_x_like_sync_state PRIMARY KEY (id)
);

CREATE TABLE x_like_sync_seen_token (
    token_sha256 BINARY(32) NOT NULL,
    CONSTRAINT pk_x_like_sync_seen_token PRIMARY KEY (token_sha256)
);
