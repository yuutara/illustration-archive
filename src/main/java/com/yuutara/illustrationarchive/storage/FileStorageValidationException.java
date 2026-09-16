package com.yuutara.illustrationarchive.storage;

/**
 * Indicates that an uploaded file or storage key does not satisfy storage rules.
 */
public class FileStorageValidationException extends RuntimeException {

	public FileStorageValidationException(String message) {
		super(message);
	}
}
