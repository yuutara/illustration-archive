package com.yuutara.illustrationarchive.service;

public class IllustrationNotFoundException extends RuntimeException {

	public IllustrationNotFoundException(long illustrationId) {
		super("Illustration not found: " + illustrationId);
	}
}
