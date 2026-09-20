package com.yuutara.illustrationarchive.dto;

import java.time.LocalDateTime;

public record AuthorDetail(
		Long id,
		String displayName,
		String xUsername,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
) {
}
