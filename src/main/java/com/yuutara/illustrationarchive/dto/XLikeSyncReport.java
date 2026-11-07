package com.yuutara.illustrationarchive.dto;

/**
 * Counts describe this operation, never the full Pending Inbox. No pagination tokens are exposed.
 * {@code readLimitNeeded} is set when History has more but this limit cannot reach it after Recent's check of the
 * newest page: the smallest per-sync read that would. Null otherwise.
 */
public record XLikeSyncReport(Phase recent, Phase history, int pageBudget, int scanBudget,
		int newBudget, boolean partial, String historyStatus, String recentStatus, Integer readLimitNeeded) {
	public XLikeSyncReport(Phase recent, Phase history, int pageBudget, int scanBudget,
			int newBudget, boolean partial, String historyStatus, String recentStatus) {
		this(recent, history, pageBudget, scanBudget, newBudget, partial, historyStatus, recentStatus, null);
	}
	public XLikeSyncReport(Phase recent, Phase history, int pageBudget, int scanBudget,
			int newBudget, boolean partial, String historyStatus) {
		this(recent, history, pageBudget, scanBudget, newBudget, partial, historyStatus, "UNKNOWN");
	}
	/**
	 * accountStatus: OK, CHANGED (progress belongs to another account) or UNATTRIBUTED (saved before owners were
	 * recorded). currentAccount and currentAccountId name the signed-in account a sync refused, and are null while the
	 * status is OK. accountConfirmation is sent back with the user's answer, which only counts while it still matches.
	 */
	public record Progress(String recentStatus, String historyStatus, boolean unscannedIntervals, String accountStatus,
			String currentAccount, String currentAccountId, String accountConfirmation) { }
	public record Phase(int pagesRequested, int pagesSaved, int scannedCount, int reservedScanCount,
			int newCount, int existingCount, int processedCount, int unsupportedCount,
			boolean scanCountKnown, boolean hasMore, String stopReason, Integer upstreamStatus) {
	}
}
