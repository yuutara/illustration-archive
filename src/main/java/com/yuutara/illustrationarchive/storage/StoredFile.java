package com.yuutara.illustrationarchive.storage;

/**
 * Metadata needed to create an Asset record after a file has been saved,
 * including the SHA-256 of the complete source file.
 */
public record StoredFile(
		String originalFilename,
		String storageKey,
		String mimeType,
		long fileSize,
		String sha256
) {
}
