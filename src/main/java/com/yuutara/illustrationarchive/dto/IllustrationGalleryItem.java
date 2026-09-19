package com.yuutara.illustrationarchive.dto;

import java.time.LocalDateTime;

public record IllustrationGalleryItem(
		Long id,
		String title,
		AuthorSummary author,
		Long coverAssetId,
		int assetCount,
		LocalDateTime createdAt
) {
}
