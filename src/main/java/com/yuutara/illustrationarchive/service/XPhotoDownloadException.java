package com.yuutara.illustrationarchive.service;

public class XPhotoDownloadException extends RuntimeException {
	private final Integer upstreamStatus;

	public XPhotoDownloadException(String message) {
		this(message, null, null);
	}

	public XPhotoDownloadException(String message, Throwable cause) {
		this(message, null, cause);
	}

	public XPhotoDownloadException(String message, Integer upstreamStatus) {
		this(message, upstreamStatus, null);
	}

	private XPhotoDownloadException(String message, Integer upstreamStatus, Throwable cause) {
		super(message, cause);
		this.upstreamStatus = upstreamStatus;
	}

	public Integer upstreamStatus() {
		return upstreamStatus;
	}
}
