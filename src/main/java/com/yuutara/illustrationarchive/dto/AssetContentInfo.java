package com.yuutara.illustrationarchive.dto;

/**
 * Internal metadata needed to load an asset's stored file.
 */
public record AssetContentInfo(
		String storageKey,
		String mimeType,
		long fileSize
) {
}
