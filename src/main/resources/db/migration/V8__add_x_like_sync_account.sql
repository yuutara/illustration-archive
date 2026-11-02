-- Which X account the saved Recent / History progress belongs to; id=1 is the only row.
-- Nothing is backfilled: no owner means nobody recorded one, which is not the same as "the account signed in now".
-- observed_* is the account a sync last found that may not continue this progress, kept so the Inbox can name it
-- without paying to ask X again.
CREATE TABLE x_like_sync_account (
    id TINYINT NOT NULL,
    owner_x_user_id VARCHAR(32) NULL,
    observed_x_user_id VARCHAR(32) NULL,
    observed_x_username VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
