package com.yuutara.illustrationarchive.dto;

import java.util.List;

public record XLikeInboxPage(List<XLikeInboxItem> items, int page, int size,
		long totalItems, int totalPages, XLikeSyncReport.Progress syncProgress) {
	public XLikeInboxPage(List<XLikeInboxItem> items, int page, int size, long totalItems, int totalPages) {
		this(items, page, size, totalItems, totalPages, null);
	}
}
