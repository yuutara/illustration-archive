package com.yuutara.illustrationarchive.repository;

public record AssetThumbnailBackfillCandidate(
		long assetId,
		String storageKey,
		String mimeType
) {
}
