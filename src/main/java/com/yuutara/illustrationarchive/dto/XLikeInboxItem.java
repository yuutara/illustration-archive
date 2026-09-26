package com.yuutara.illustrationarchive.dto;

import java.time.Instant;
import java.util.List;

public record XLikeInboxItem(long id, String xPostId, String authorDisplayName,
		String authorUsername, String postText, Instant postCreatedAt, List<XLikeMedia> media) {
}
