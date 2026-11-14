package com.yuutara.illustrationarchive.dto;

import java.time.Instant;

public record TrashItem(
		long id,
		String title,
		AuthorSummary author,
		String sourceUrl,
		Long coverAssetId,
		String coverMimeType,
		int assetCount,
		Instant trashedAt,
		Instant purgeAt
) {
}
