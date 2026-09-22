package com.yuutara.illustrationarchive.service;

public record Sha256BackfillResult(
		long assetId,
		String storageKey,
		String status,
		String sha256,
		Long existingAssetId,
		String errorMessage
) {

	public static final String UPDATED = "UPDATED";
	public static final String DUPLICATE = "DUPLICATE";
	public static final String FAILED = "FAILED";

	public static Sha256BackfillResult updated(long assetId, String storageKey, String sha256) {
		return new Sha256BackfillResult(assetId, storageKey, UPDATED, sha256, null, null);
	}

	public static Sha256BackfillResult duplicate(
			long assetId,
			String storageKey,
			String sha256,
			long existingAssetId
	) {
		return new Sha256BackfillResult(assetId, storageKey, DUPLICATE, sha256, existingAssetId, null);
	}

	public static Sha256BackfillResult failure(long assetId, String storageKey, String errorMessage) {
		return new Sha256BackfillResult(assetId, storageKey, FAILED, null, null, errorMessage);
	}
}
