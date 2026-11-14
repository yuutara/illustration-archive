package com.yuutara.illustrationarchive.dto;

import java.util.List;

public record TrashPage(
		List<TrashItem> items,
		int page,
		int size,
		long totalItems,
		int totalPages,
		int retentionDays
) {
}
