package com.yuutara.illustrationarchive.desktop;

/** A backup or restore that stopped. The message is written for the user; the cause, when there is one, goes to the log. */
final class MaintenanceException extends Exception {

	private enum Kind { REFUSED, CANCELLED, STUCK }

	private final Kind kind;

	MaintenanceException(String message) {
		this(message, null);
	}

	MaintenanceException(String message, Throwable cause) {
		this(message, cause, Kind.REFUSED);
	}

	private MaintenanceException(String message, Throwable cause, Kind kind) {
		super(message, cause);
		this.kind = kind;
	}

	static MaintenanceException cancelled() {
		return new MaintenanceException("已取消。", null, Kind.CANCELLED);
	}

	/**
	 * The library is not known to be whole where it belongs: a switch could not be undone, or its record disagrees
	 * with the folders. Nothing may open the database then, or it would create an empty library in its place.
	 */
	static MaintenanceException stuck(String message, Throwable cause) {
		return new MaintenanceException(message, cause, Kind.STUCK);
	}

	boolean isCancelled() {
		return kind == Kind.CANCELLED;
	}

	boolean isStuck() {
		return kind == Kind.STUCK;
	}
}
