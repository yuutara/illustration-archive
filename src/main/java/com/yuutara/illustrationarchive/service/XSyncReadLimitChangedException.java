package com.yuutara.illustrationarchive.service;

/** The saved read limit is no longer the one the user saw when pressing Sync; nothing was read from X. */
public class XSyncReadLimitChangedException extends RuntimeException {
	private final int readLimit;

	public XSyncReadLimitChangedException(int readLimit) {
		super("The read limit changed to " + readLimit + " since the page showed it. Nothing was read from X.");
		this.readLimit = readLimit;
	}

	public int readLimit() {
		return readLimit;
	}
}
