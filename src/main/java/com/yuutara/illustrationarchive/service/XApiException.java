package com.yuutara.illustrationarchive.service;

public class XApiException extends RuntimeException {
	private final Integer upstreamStatus;
	private final Integer scannedCount;

	public XApiException(String message, Integer upstreamStatus) {
		this(message, upstreamStatus, null);
	}

	public XApiException(String message, Integer upstreamStatus, Integer scannedCount) {
		super(message);
		this.upstreamStatus = upstreamStatus;
		this.scannedCount = scannedCount;
	}

	public Integer upstreamStatus() {
		return upstreamStatus;
	}

	public Integer scannedCount() { return scannedCount; }
}
