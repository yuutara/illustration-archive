package com.yuutara.illustrationarchive.repository;

public record AssetSha256BackfillCandidate(
		long assetId,
		String storageKey
) {
}
