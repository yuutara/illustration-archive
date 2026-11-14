package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.desktop.MaintenanceGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs 30-day Trash expiration and pending file-cleanup retries on desktop startup and periodically
 * while the desktop app remains open, and coordinates shutdown drainage before the database pool closes.
 */
@Component
@ConditionalOnProperty(name = "illustration-archive.ui.desktop", havingValue = "true")
public class TrashPurgeScheduler implements SmartLifecycle, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(TrashPurgeScheduler.class);
	private static final long CHECK_INTERVAL_MINUTES = 60L;
	static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(60);

	private final IllustrationTrashService trashService;
	private final MaintenanceGate maintenanceGate;
	private final Duration drainTimeout;
	private final AtomicBoolean stopping = new AtomicBoolean(false);
	private final AtomicBoolean lifecycleRunning = new AtomicBoolean(false);
	private final AtomicBoolean scheduled = new AtomicBoolean(false);
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "trash-purge");
		thread.setDaemon(true);
		return thread;
	});

	@Autowired
	public TrashPurgeScheduler(IllustrationTrashService trashService,
			@Autowired(required = false) MaintenanceGate maintenanceGate) {
		this(trashService, maintenanceGate, DEFAULT_DRAIN_TIMEOUT);
	}

	public TrashPurgeScheduler(IllustrationTrashService trashService) {
		this(trashService, null, DEFAULT_DRAIN_TIMEOUT);
	}

	TrashPurgeScheduler(IllustrationTrashService trashService, MaintenanceGate maintenanceGate, Duration drainTimeout) {
		this.trashService = trashService;
		this.maintenanceGate = maintenanceGate;
		this.drainTimeout = drainTimeout;
	}

	@Override
	public void start() {
		lifecycleRunning.set(true);
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public boolean isRunning() {
		return lifecycleRunning.get();
	}

	@Override
	public int getPhase() {
		// Stop before Tomcat and bean destruction so new work is refused as soon as context shutdown starts.
		return Integer.MAX_VALUE;
	}

	@Override
	public void stop() {
		lifecycleRunning.set(false);
		requestStop();
		executor.shutdown();
	}

	@EventListener(ApplicationReadyEvent.class)
	void scheduleOnReady() {
		if (!stopping.get() && scheduled.compareAndSet(false, true)) {
			executor.scheduleWithFixedDelay(this::runOnce, 0L, CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES);
		}
	}

	public void requestStop() {
		stopping.set(true);
		if (maintenanceGate != null) {
			maintenanceGate.closeForShutdown();
		}
	}

	public void resumeAfterCancelledStop() {
		stopping.set(false);
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
		lifecycleRunning.set(false);
		requestStop();
		executor.shutdown();
		try {
			if (!executor.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
				log.warn("Timed out after {}s waiting for background Trash cleanup to finish during shutdown.",
						drainTimeout.toSeconds());
				executor.shutdownNow();
			}
			if (maintenanceGate != null && !maintenanceGate.close(drainTimeout, () -> false)) {
				log.warn("Timed out waiting for MaintenanceGate to drain before closing DataSource.");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
