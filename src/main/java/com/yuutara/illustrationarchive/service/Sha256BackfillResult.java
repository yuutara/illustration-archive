package com.yuutara.illustrationarchive.service;

public record Sha256BackfillResult(
		long assetId,
		String storageKey,
		String sha256,
		String errorMessage
) {

	public static Sha256BackfillResult success(long assetId, String storageKey, String sha256) {
		return new Sha256BackfillResult(assetId, storageKey, sha256, null);
	}

	public static Sha256BackfillResult failure(long assetId, String storageKey, String errorMessage) {
		return new Sha256BackfillResult(assetId, storageKey, null, errorMessage);
	}
}
