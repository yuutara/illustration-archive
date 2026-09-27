package com.yuutara.illustrationarchive.dto;

public record XLikeSyncSummary(int pagesFetched, int fetchedCount, int newCount, int existingCount,
		int pendingCount, int unsupportedCount, boolean hasMore,
		boolean stoppedByMaxPages, boolean stoppedByInvalidToken) {
}
