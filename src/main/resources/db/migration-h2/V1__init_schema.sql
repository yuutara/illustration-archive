-- H2 equivalent of db/migration/V1 for the independent desktop PoC database.
-- The connection runs with IGNORECASE=TRUE, so VARCHAR columns compare like MySQL utf8mb4_unicode_ci.
-- MySQL TEXT becomes VARCHAR(65535) here: H2 CLOB would ignore IGNORECASE and make search case-sensitive.
CREATE TABLE author (
    id BIGINT NOT NULL AUTO_INCREMENT,
    display_name VARCHAR(255) NOT NULL,
    x_username VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_author PRIMARY KEY (id),
    CONSTRAINT uk_author_x_username UNIQUE (x_username)
);

CREATE TABLE illustration (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NULL,
    author_id BIGINT NULL,
    source_url VARCHAR(2048) NULL,
    note VARCHAR(65535) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_illustration PRIMARY KEY (id),
    CONSTRAINT fk_illustration_author FOREIGN KEY (author_id) REFERENCES author (id) ON DELETE SET NULL
);
CREATE INDEX idx_illustration_created_at_id ON illustration (created_at, id);

CREATE TABLE asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    illustration_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    sort_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_asset PRIMARY KEY (id),
    CONSTRAINT uk_asset_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_asset_illustration FOREIGN KEY (illustration_id) REFERENCES illustration (id) ON DELETE CASCADE
);

CREATE TABLE tag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_tag PRIMARY KEY (id),
    CONSTRAINT uk_tag_name UNIQUE (name)
);

CREATE TABLE illustration_tag (
    illustration_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    CONSTRAINT pk_illustration_tag PRIMARY KEY (illustration_id, tag_id),
    CONSTRAINT fk_illustration_tag_illustration FOREIGN KEY (illustration_id) REFERENCES illustration (id) ON DELETE CASCADE,
    CONSTRAINT fk_illustration_tag_tag FOREIGN KEY (tag_id) REFERENCES tag (id) ON DELETE CASCADE
);
CREATE INDEX idx_illustration_tag_tag_id ON illustration_tag (tag_id);
