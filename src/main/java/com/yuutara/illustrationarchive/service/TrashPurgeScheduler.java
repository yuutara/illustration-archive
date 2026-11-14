package com.yuutara.illustrationarchive.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs 30-day Trash expiration and pending file-cleanup retries on desktop startup and periodically
 * while the desktop app remains open.
 */
@Component
@ConditionalOnProperty(name = "illustration-archive.ui.desktop", havingValue = "true")
public class TrashPurgeScheduler implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(TrashPurgeScheduler.class);
	private static final long CHECK_INTERVAL_MINUTES = 60L;

	private final IllustrationTrashService trashService;
	private final AtomicBoolean stopping = new AtomicBoolean(false);
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "trash-purge");
		thread.setDaemon(true);
		return thread;
	});

	public TrashPurgeScheduler(IllustrationTrashService trashService) {
		this.trashService = trashService;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (stopping.get()) {
			return;
		}
		executor.scheduleWithFixedDelay(this::runOnce, 0L, CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES);
	}

	void runOnce() {
		if (stopping.get()) {
			return;
		}
		try {
			trashService.purgeExpiredAndCleanup(stopping::get);
		} catch (RuntimeException exception) {
			log.warn("Scheduled Trash expiration check failed.", exception);
		}
	}

	@Override
	public void destroy() {
		stopping.set(true);
		executor.shutdownNow();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
