package com.yuutara.illustrationarchive.service;

public class TagNotFoundException extends RuntimeException {

	public TagNotFoundException(long tagId) {
		super("Tag not found: " + tagId);
	}
}
