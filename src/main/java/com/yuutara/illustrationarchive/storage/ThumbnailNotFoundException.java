package com.yuutara.illustrationarchive.storage;

public class ThumbnailNotFoundException extends RuntimeException {

	public ThumbnailNotFoundException() {
		super("Thumbnail not found.");
	}
}
