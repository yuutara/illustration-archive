package com.yuutara.illustrationarchive.dto;

import java.util.List;

public record XLikeInboxPage(List<XLikeInboxItem> items, int page, int size,
		long totalItems, int totalPages) {
}
