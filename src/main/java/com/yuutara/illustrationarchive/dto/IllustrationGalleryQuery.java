package com.yuutara.illustrationarchive.dto;

import java.util.List;

public record IllustrationGalleryQuery(String q, Long authorId, Long tagId,
        List<Long> authorIds, List<Long> tagIds) {

    public IllustrationGalleryQuery(String q, Long authorId, Long tagId) {
        this(q, authorId, tagId, authorId == null ? List.of() : List.of(authorId),
                tagId == null ? List.of() : List.of(tagId));
    }

    public static IllustrationGalleryQuery withIds(String q, List<Long> authorIds, List<Long> tagIds) {
        return new IllustrationGalleryQuery(q, null, null, authorIds, tagIds);
    }

	public IllustrationGalleryQuery {
		q = q == null || q.isBlank() ? null : q.strip();
		if (q != null && q.codePointCount(0, q.length()) > 200) {
			throw new IllegalArgumentException("Search text must not exceed 200 characters.");
		}
        authorIds = normalizeIds(authorIds);
        tagIds = normalizeIds(tagIds);
        authorId = authorIds.size() == 1 ? authorIds.get(0) : null;
        tagId = tagIds.size() == 1 ? tagIds.get(0) : null;
	}

    private static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null) return List.of();
        if (ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Author and tag ids must be positive; at most 100 per category.");
        }
        return ids.stream().distinct().sorted().toList();
    }
}
