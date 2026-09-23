package com.yuutara.illustrationarchive.service;

public record ThumbnailBackfillResult(
		long assetId,
		String storageKey,
		String status,
		String errorMessage
) {

	public static final String READY = "READY";
	public static final String SKIPPED_GIF = "SKIPPED_GIF";
	public static final String FAILED = "FAILED";

	public static ThumbnailBackfillResult ready(long assetId, String storageKey) {
		return new ThumbnailBackfillResult(assetId, storageKey, READY, null);
	}

	public static ThumbnailBackfillResult skippedGif(long assetId, String storageKey) {
		return new ThumbnailBackfillResult(assetId, storageKey, SKIPPED_GIF, null);
	}

	public static ThumbnailBackfillResult failure(long assetId, String storageKey, String errorMessage) {
		return new ThumbnailBackfillResult(assetId, storageKey, FAILED, errorMessage);
	}
}
