package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AuthorSummary;

import java.time.LocalDateTime;

/**
 * Internal main-record portion of an illustration detail query.
 */
public record IllustrationDetailBase(
		Long id,
		String title,
		AuthorSummary author,
		String sourceUrl,
		String note,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
) {
}
