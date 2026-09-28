package com.yuutara.illustrationarchive.storage;

/**
 * Indicates that the selected storage backend could not complete an operation.
 */
public class FileStorageException extends RuntimeException {

	public FileStorageException(String message) {
		super(message);
	}

	public FileStorageException(String message, Throwable cause) {
		super(message, cause);
	}
}
