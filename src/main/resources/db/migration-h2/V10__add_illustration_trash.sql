-- H2 equivalent of db/migration/V10__add_illustration_trash.sql.
ALTER TABLE illustration
    ADD COLUMN trashed_at TIMESTAMP(3) NULL;

CREATE INDEX idx_illustration_trashed_at_id ON illustration (trashed_at, id);

CREATE TABLE pending_asset_cleanup (
    id BIGINT NOT NULL AUTO_INCREMENT,
    storage_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT pk_pending_asset_cleanup PRIMARY KEY (id)
);

CREATE INDEX idx_pending_asset_cleanup_created ON pending_asset_cleanup (created_at, id);
