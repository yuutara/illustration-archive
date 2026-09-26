package com.yuutara.illustrationarchive.dto;

import java.time.Instant;
import java.util.List;

public record XLikeCandidate(String xPostId, String xAuthorId, String authorUsername,
		String authorDisplayName, String postText, Instant postCreatedAt,
		XLikeStatus status, List<XLikeMedia> media) {
}
