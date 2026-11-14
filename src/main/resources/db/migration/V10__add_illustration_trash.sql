-- Soft-delete timestamp for desktop Trash (NULL = active gallery work, non-NULL = in Trash).
-- Server mode leaves trashed_at NULL.
ALTER TABLE `illustration`
    ADD COLUMN `trashed_at` DATETIME(3) NULL,
    ADD KEY `idx_illustration_trashed_at_id` (`trashed_at`, `id`);

-- Pending storage deletions written in the same transaction as permanent illustration deletion,
-- so post-commit file cleanup failures or crashes can be retried later.
CREATE TABLE `pending_asset_cleanup` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `storage_key` VARCHAR(512) NOT NULL,
    `mime_type` VARCHAR(100) NOT NULL,
    `created_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_pending_asset_cleanup_created` (`created_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
