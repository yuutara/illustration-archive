package com.yuutara.illustrationarchive.desktop;

import java.util.function.BooleanSupplier;

/**
 * What the launcher's start thread does before the window can show the library: read whether this version will
 * upgrade it, back it up if so, then start the server, which is when Flyway upgrades it. Quitting is honored at every
 * step: a backup in progress stops and leaves nothing, and a quit that arrives once the backup is done still keeps the
 * server, and so the upgrade, from starting.
 *
 * <p>The thread may still be reading or writing the library when quitting begins, so the launcher waits for it
 * ({@link #awaitEnd}) before it gives up the instance lock; a second launch cannot open the library meanwhile.
 */
final class LibraryStart {

	/** The parts that belong to the launcher. Reports arrive on the start thread. */
	interface Steps {

		/** @throws MaintenanceException when the library cannot be read at all */
		boolean needsUpgrade() throws MaintenanceException;

		void backUp(LibraryBackup.Progress progress) throws MaintenanceException;

		void progress(String stage, double fraction);

		/** The backup is written and checked; the upgrade comes next. */
		void backedUp();

		/** Starts the server on the library, upgrading it; the launcher deals with its outcome. */
		void startServer();

		/** The library did not open, so nothing was backed up or changed; a restore is the way on. */
		void unreadable(String message);

		/** The library is as it was, and opens only if the user says so. */
		void backupFailed(String message);
	}

	private final Steps steps;
	private final BooleanSupplier quitting;
	private Thread thread;

	LibraryStart(Steps steps, BooleanSupplier quitting) {
		this.steps = steps;
		this.quitting = quitting;
	}

	/**
	 * @param backUpFirst false only once the user chose to open without a backup
	 * @param loader the application's class loader, which Spring and Flyway look up resources with
	 */
	synchronized void begin(boolean backUpFirst, ClassLoader loader) {
		thread = new Thread(() -> {
			Thread.currentThread().setContextClassLoader(loader);
			run(backUpFirst);
		}, "desktop-start");
		thread.start();
	}

	/** The start sequence itself, on the calling thread. */
	void run(boolean backUpFirst) {
		if (backUpFirst && !backUp()) {
			return;
		}
		if (quitting.getAsBoolean()) {
			return;
		}
		steps.startServer();
	}

	private boolean backUp() {
		boolean upgrade;
		try {
			upgrade = steps.needsUpgrade();
		} catch (MaintenanceException unreadable) {
			steps.unreadable(unreadable.getMessage());
			return false;
		}
		if (!upgrade) {
			return true;
		}
		try {
			steps.backUp(new LibraryBackup.Progress() {
				@Override
				public void update(String stage, double fraction) {
					steps.progress(stage, fraction);
				}

				@Override
				public boolean cancelled() {
					return quitting.getAsBoolean();
				}
			});
		} catch (MaintenanceException failed) {
			if (!failed.isCancelled() && !quitting.getAsBoolean()) {
				steps.backupFailed(failed.getMessage());
			}
			return false;
		}
		steps.backedUp();
		return true;
	}

	/** Waits for the start thread, however long an upgrade already under way takes. */
	void awaitEnd() {
		Thread running;
		synchronized (this) {
			running = thread;
		}
		if (running == null) {
			return;
		}
		boolean interrupted = false;
		while (running.isAlive()) {
			try {
				running.join();
			} catch (InterruptedException again) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
