package com.yuutara.illustrationarchive.desktop;

/** A backup or restore that stopped. The message is written for the user; the cause, when there is one, goes to the log. */
final class MaintenanceException extends Exception {

	private final boolean cancelled;

	MaintenanceException(String message) {
		this(message, null);
	}

	MaintenanceException(String message, Throwable cause) {
		this(message, cause, false);
	}

	private MaintenanceException(String message, Throwable cause, boolean cancelled) {
		super(message, cause);
		this.cancelled = cancelled;
	}

	static MaintenanceException cancelled() {
		return new MaintenanceException("已取消。", null, true);
	}

	boolean isCancelled() {
		return cancelled;
	}
}
