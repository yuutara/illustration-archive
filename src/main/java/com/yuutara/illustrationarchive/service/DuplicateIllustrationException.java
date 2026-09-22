package com.yuutara.illustrationarchive.service;

public class DuplicateIllustrationException extends RuntimeException {

	public static final String ERROR_CODE = "DUPLICATE_IMAGE";

	private final long illustrationId;

	public DuplicateIllustrationException(long illustrationId) {
		super("An illustration with the same image already exists.");
		this.illustrationId = illustrationId;
	}

	public long illustrationId() {
		return illustrationId;
	}
}
