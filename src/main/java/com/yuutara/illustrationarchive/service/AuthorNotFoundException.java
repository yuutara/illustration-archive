package com.yuutara.illustrationarchive.service;

public class AuthorNotFoundException extends RuntimeException {

	public AuthorNotFoundException(long authorId) {
		super("Author not found: " + authorId);
	}
}
