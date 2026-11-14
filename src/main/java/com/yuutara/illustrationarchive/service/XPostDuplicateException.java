package com.yuutara.illustrationarchive.service;

public class XPostDuplicateException extends RuntimeException {
	private final Long illustrationId;
	private final boolean trashed;

	public XPostDuplicateException(String message) {
		this(message, null, false);
	}

	public XPostDuplicateException(String message, Long illustrationId, boolean trashed) {
		super(message);
		this.illustrationId = illustrationId;
		this.trashed = trashed;
	}

	public Long illustrationId() {
		return illustrationId;
	}

	public boolean trashed() {
		return trashed;
	}
}
