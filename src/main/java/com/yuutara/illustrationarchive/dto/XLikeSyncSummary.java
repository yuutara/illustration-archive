package com.yuutara.illustrationarchive.dto;

public record XLikeSyncSummary(int fetchedCount, int newCount, int existingCount,
		int pendingCount, int unsupportedCount, boolean hasMore) {
}
