package com.yuutara.illustrationarchive.dto;

/** Counts describe this operation, never the full Pending Inbox. No pagination tokens are exposed. */
public record XLikeSyncReport(Phase recent, Phase history, int pageBudget, int scanBudget,
		int newBudget, boolean partial, String historyStatus, String recentStatus) {
	public XLikeSyncReport(Phase recent, Phase history, int pageBudget, int scanBudget,
			int newBudget, boolean partial, String historyStatus) {
		this(recent, history, pageBudget, scanBudget, newBudget, partial, historyStatus, "UNKNOWN");
	}
	public record Progress(String recentStatus, String historyStatus, boolean unscannedIntervals) { }
	public record Phase(int pagesRequested, int pagesSaved, int scannedCount, int reservedScanCount,
			int newCount, int existingCount, int processedCount, int unsupportedCount,
			boolean scanCountKnown, boolean hasMore, String stopReason, Integer upstreamStatus) {
	}
}
