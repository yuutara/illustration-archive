package com.yuutara.illustrationarchive.dto;

public record IllustrationGalleryQuery(String q, Long authorId, Long tagId) {

	public IllustrationGalleryQuery {
		q = q == null || q.isBlank() ? null : q.strip();
		if (q != null && q.codePointCount(0, q.length()) > 200) {
			throw new IllegalArgumentException("Search text must not exceed 200 characters.");
		}
		if (authorId != null && authorId <= 0 || tagId != null && tagId <= 0) {
			throw new IllegalArgumentException("Author and tag ids must be positive.");
		}
	}
}
