package com.yuutara.illustrationarchive.dto;

public record AssetSummary(
		Long id,
		String originalFilename,
		String mimeType,
		long fileSize,
		int sortOrder
) {
}
