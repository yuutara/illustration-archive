package com.yuutara.illustrationarchive.dto;

import java.util.List;

public record IllustrationGalleryFilters(
		String q, Long authorId, Long tagId, AuthorSummary author, TagSummary tag,
        List<Long> authorIds, List<Long> tagIds, List<AuthorSummary> authors, List<TagSummary> tags
) {
}
