package com.yuutara.illustrationarchive.dto;

import java.time.LocalDateTime;
import java.util.List;

public record IllustrationDetail(
		Long id,
		String title,
		AuthorSummary author,
		String sourceUrl,
		String note,
		List<AssetSummary> assets,
		List<TagSummary> tags,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
) {
}
