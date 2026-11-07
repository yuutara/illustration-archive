package com.yuutara.illustrationarchive.service;

/** Where the desktop app keeps the user's "read at most this many Likes per sync"; server mode has none. */
@FunctionalInterface
public interface XLikeSyncReadLimit {
	/** Null keeps the configured {@code x.sync.*} budget. */
	Integer reads();
}
