CREATE TABLE `author` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `display_name` VARCHAR(255) NOT NULL,
    `x_username` VARCHAR(64) NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_author_x_username` (`x_username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `illustration` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `title` VARCHAR(255) NULL,
    `author_id` BIGINT NULL,
    `source_url` VARCHAR(2048) NULL,
    `note` TEXT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_illustration_created_at_id` (`created_at`, `id`),
    CONSTRAINT `fk_illustration_author`
        FOREIGN KEY (`author_id`) REFERENCES `author` (`id`)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `asset` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `illustration_id` BIGINT NOT NULL,
    `original_filename` VARCHAR(255) NOT NULL,
    `storage_key` VARCHAR(512) NOT NULL,
    `mime_type` VARCHAR(100) NOT NULL,
    `file_size` BIGINT NOT NULL,
    `sort_order` INT NOT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_asset_storage_key` (`storage_key`),
    CONSTRAINT `fk_asset_illustration`
        FOREIGN KEY (`illustration_id`) REFERENCES `illustration` (`id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tag` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tag_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `illustration_tag` (
    `illustration_id` BIGINT NOT NULL,
    `tag_id` BIGINT NOT NULL,
    PRIMARY KEY (`illustration_id`, `tag_id`),
    KEY `idx_illustration_tag_tag_id` (`tag_id`),
    CONSTRAINT `fk_illustration_tag_illustration`
        FOREIGN KEY (`illustration_id`) REFERENCES `illustration` (`id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_illustration_tag_tag`
        FOREIGN KEY (`tag_id`) REFERENCES `tag` (`id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
