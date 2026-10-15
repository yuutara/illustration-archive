package com.yuutara.illustrationarchive.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounds Likes HTTP attempts, including credential retries, before sending a request. */
@Component
public record XLikeSyncBudget(
		@Value("${x.sync.recent-page-size:25}") int recentPageSize,
		@Value("${x.sync.recent-max-pages:2}") int recentMaxPages,
		@Value("${x.sync.max-pages:6}") int maxPages,
		@Value("${x.sync.max-scanned:100}") int maxScanned,
		@Value("${x.sync.max-new:50}") int maxNew) {
	public XLikeSyncBudget {
		if (recentPageSize < 5 || recentPageSize > 100 || recentMaxPages < 1
				|| maxPages < recentMaxPages || maxPages > 10 || maxScanned < recentPageSize
				|| maxScanned > 1000 || maxNew < recentPageSize || maxNew > 1000) {
			throw new IllegalArgumentException("Invalid X Likes sync budget.");
		}
	}

	static final class Usage {
		final XLikeSyncBudget limits;
		int pages;
		int reserved;
		int added;
		Usage(XLikeSyncBudget limits) { this.limits = limits; }
		String stopBefore(int size) {
			if (pages >= limits.maxPages()) return "PAGE_BUDGET";
			if (size > limits.maxScanned() - reserved) return "SCAN_BUDGET";
			// Do not discard a page tail or change the page size attached to a saved token.
			if (size > limits.maxNew() - added) return "NEW_BUDGET";
			return null;
		}
		void reserve(int size) {
			String reason = stopBefore(size);
			if (reason != null) throw new LimitReached(reason);
			pages++;
			reserved += size;
		}
	}

	static final class LimitReached extends RuntimeException {
		final String reason;
		LimitReached(String reason) { super("X Likes request budget reached."); this.reason = reason; }
	}
}
