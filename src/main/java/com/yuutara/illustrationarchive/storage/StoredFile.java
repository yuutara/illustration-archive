package com.yuutara.illustrationarchive.storage;

/**
 * Metadata needed to create an Asset record after a file has been saved.
 */
public record StoredFile(
		String originalFilename,
		String storageKey,
		String mimeType,
		long fileSize
) {
}
