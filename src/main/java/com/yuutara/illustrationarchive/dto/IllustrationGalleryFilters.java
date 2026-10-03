package com.yuutara.illustrationarchive.dto;

public record IllustrationGalleryFilters(
		String q, Long authorId, Long tagId, AuthorSummary author, TagSummary tag
) {
}
