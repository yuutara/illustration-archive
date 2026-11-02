package com.yuutara.illustrationarchive.service;

/** What the user confirmed about the X account is no longer what is saved, so nothing was done. */
public class XSyncConfirmationExpiredException extends RuntimeException {
	public XSyncConfirmationExpiredException() {
		super("The X account state has changed since this was confirmed.");
	}
}
