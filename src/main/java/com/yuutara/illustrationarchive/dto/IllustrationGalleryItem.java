package com.yuutara.illustrationarchive.dto;

import java.time.LocalDateTime;
import java.util.List;

public record IllustrationGalleryItem(
		Long id,
		String title,
		AuthorSummary author,
		Long coverAssetId,
		String coverMimeType,
		int assetCount,
		LocalDateTime createdAt,
		List<GalleryAsset> assets
) {
}
