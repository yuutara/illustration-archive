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

	/** A list row before its page assets are attached (used by the gallery mapper). */
	public IllustrationGalleryItem(Long id, String title, AuthorSummary author, Long coverAssetId,
			String coverMimeType, int assetCount, LocalDateTime createdAt) {
		this(id, title, author, coverAssetId, coverMimeType, assetCount, createdAt, List.of());
	}
}
