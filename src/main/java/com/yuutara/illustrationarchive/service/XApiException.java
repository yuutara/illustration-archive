package com.yuutara.illustrationarchive.service;

public class XApiException extends RuntimeException {
	private final Integer upstreamStatus;

	public XApiException(String message, Integer upstreamStatus) {
		super(message);
		this.upstreamStatus = upstreamStatus;
	}

	public Integer upstreamStatus() {
		return upstreamStatus;
	}
}
