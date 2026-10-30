package com.yuutara.illustrationarchive.dto;

/**
 * Gallery list orders. Every order ends on a unique key so offset pages neither skip nor repeat.
 * NEWEST and OLDEST use {@code illustration.created_at}, the moment a work entered the archive.
 */
public enum IllustrationGallerySort {
	NEWEST, OLDEST, TITLE, AUTHOR;

	/** Absent or blank means the default order; anything unknown is an invalid request. */
	public static IllustrationGallerySort fromParam(String value) {
		if (value == null || value.isBlank()) {
			return NEWEST;
		}
		for (IllustrationGallerySort sort : values()) {
			if (sort.name().equalsIgnoreCase(value.strip())) {
				return sort;
			}
		}
		throw new IllegalArgumentException("Sort must be one of newest, oldest, title or author.");
	}
}
