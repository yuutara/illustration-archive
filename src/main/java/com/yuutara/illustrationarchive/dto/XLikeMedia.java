package com.yuutara.illustrationarchive.dto;

public record XLikeMedia(String mediaKey, int sortOrder, String mediaType,
		String sourceUrl, Integer width, Integer height) {
}
