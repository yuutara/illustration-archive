package com.yuutara.illustrationarchive.dto;

import java.util.List;

public record IllustrationGalleryPage(
		int page,
		int size,
		long totalElements,
		int totalPages,
		List<IllustrationGalleryItem> items
) {
}
