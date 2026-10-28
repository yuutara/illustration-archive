package com.yuutara.illustrationarchive.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A missing illustration is a 404 for detail, edit and delete requests, not a server failure. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class IllustrationNotFoundException extends RuntimeException {

	public IllustrationNotFoundException(long illustrationId) {
		super("Illustration not found: " + illustrationId);
	}
}
