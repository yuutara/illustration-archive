package com.yuutara.illustrationarchive.storage;

/**
 * Indicates that a stored image could not be decoded, resized, or encoded as a thumbnail.
 */
public class ThumbnailGenerationException extends RuntimeException {

	public ThumbnailGenerationException(String message) {
		super(message);
	}

	public ThumbnailGenerationException(String message, Throwable cause) {
		super(message, cause);
	}
}
