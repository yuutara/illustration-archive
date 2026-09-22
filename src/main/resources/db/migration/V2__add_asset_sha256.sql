ALTER TABLE `asset`
    ADD COLUMN `sha256` CHAR(64) NULL,
    ADD UNIQUE INDEX `uk_asset_sha256` (`sha256`);
