package com.yuutara.illustrationarchive.storage;

/**
 * Indicates that the local file system could not complete a storage operation.
 */
public class FileStorageException extends RuntimeException {

	public FileStorageException(String message) {
		super(message);
	}

	public FileStorageException(String message, Throwable cause) {
		super(message, cause);
	}
}
