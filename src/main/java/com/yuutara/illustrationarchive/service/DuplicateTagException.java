package com.yuutara.illustrationarchive.service;

/** A tag with the same name already exists; names compare case-insensitively (utf8mb4_unicode_ci / H2 IGNORECASE). */
public class DuplicateTagException extends RuntimeException {

	public static final String ERROR_CODE = "DUPLICATE_TAG";

	public DuplicateTagException(String name, Throwable cause) {
		super("A tag with the same name already exists: " + name, cause);
	}
}
