package com.yuutara.illustrationarchive.service;

public class DuplicateIllustrationException extends RuntimeException {

	public static final String ERROR_CODE = "DUPLICATE_IMAGE";
	public static final String TRASHED_ERROR_CODE = "DUPLICATE_IN_TRASH";

	private final long illustrationId;
	private final boolean trashed;

	public DuplicateIllustrationException(long illustrationId) {
		this(illustrationId, false);
	}

	public DuplicateIllustrationException(long illustrationId, boolean trashed) {
		super(trashed
				? "An illustration with the same image already exists in the trash."
				: "An illustration with the same image already exists.");
		this.illustrationId = illustrationId;
		this.trashed = trashed;
	}

	public long illustrationId() {
		return illustrationId;
	}

	public boolean trashed() {
		return trashed;
	}

	public String errorCode() {
		return trashed ? TRASHED_ERROR_CODE : ERROR_CODE;
	}
}
