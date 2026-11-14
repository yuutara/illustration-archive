package com.yuutara.illustrationarchive.desktop;

import com.yuutara.illustrationarchive.IllustrationArchiveApplication;
import com.yuutara.illustrationarchive.repository.PendingAssetCleanupRepository;
import com.yuutara.illustrationarchive.repository.PendingAssetCleanupRepository.AssetFile;
import com.yuutara.illustrationarchive.storage.FileStorage;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.StoredFile;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies R44-C3: closing the desktop Spring context during a slow (>5s) non-interruptible
 * file deletion rejects new gate work immediately, keeps the Hikari pool open until the in-flight
 * cleanup finishes and updates {@code pending_asset_cleanup}, halts remaining queue items for
 * shutdown, and resumes remaining cleanups on next startup.
 */
class TrashPurgeSchedulerShutdownTest {

	@TempDir
	Path workspace;

	@AfterEach
	void resetLogging() {
		((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).reset();
	}

	@Test
	void slowUninterruptibleDeleteDrainsBeforeClosingDataSourceAndResumesRemainingQueueOnRestart() throws Exception {
		SlowDeleteController.reset();
		Path home = workspace.resolve("slow-shutdown-home");
		Path mediaRoot = Files.createDirectories(home.resolve("media").resolve("2026-10"));
		Files.writeString(mediaRoot.resolve("first.png"), "one");
		Files.writeString(mediaRoot.resolve("second.png"), "two");

		Map<String, String> props = new java.util.LinkedHashMap<>(
				DesktopServer.properties(home, 0, DesktopServer.newAccessToken()));
		props.put("illustration-archive.desktop.open-external", "false");

		DesktopServer.Running seed = DesktopServer.start(home, 0, true,
				Map.of(MaintenanceGate.START_CLOSED_PROPERTY, "true"));
		try {
			PendingAssetCleanupRepository repo = seed.context().getBean(PendingAssetCleanupRepository.class);
			repo.enqueueAll(List.of(
					new AssetFile("2026-10/first.png", "image/gif"),
					new AssetFile("2026-10/second.png", "image/gif")), Instant.now());
		} finally {
			seed.context().close();
		}

		// Boot normally with slow delete (> 5.4s, ignoring interrupts) on the first item
		SlowDeleteController.blockOnFirstDelete = true;

		String[] args = props.entrySet().stream()
				.map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
				.toArray(String[]::new);
		ConfigurableApplicationContext ctx = new SpringApplicationBuilder(IllustrationArchiveApplication.class, SlowDeleteConfig.class)
				.profiles(DesktopServer.PROFILE)
				.headless(true)
				.run(args);
		try {
			assertTrue(SlowDeleteController.enteredDelete.await(10, TimeUnit.SECONDS),
					"scheduler must begin cleaning pending entry");
			MaintenanceGate gate = ctx.getBean(MaintenanceGate.class);
			HikariDataSource pool = (HikariDataSource) ctx.getBean(javax.sql.DataSource.class);

			CompletableFuture<Void> closing = CompletableFuture.runAsync(ctx::close);

			long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (gate.tryEnterBackground() && System.nanoTime() < waitDeadline) {
				gate.leaveBackground();
				Thread.sleep(20);
			}
			assertFalse(gate.tryEnterBackground(), "MaintenanceGate must reject new work during shutdown");

			// Sleep past the previous 5-second TrashPurgeScheduler timeout and confirm HikariDataSource stayed OPEN
			Thread.sleep(5_400);
			assertFalse(pool.isClosed(), "HikariDataSource must wait while in-flight cleanup is still running (>5s)");
			assertFalse(closing.isDone(), "context.close() must not complete before cleanup finishes");

			SlowDeleteController.releaseDelete.set(true);
			closing.get(15, TimeUnit.SECONDS);
			assertTrue(pool.isClosed(), "HikariDataSource closes cleanly once active cleanup finishes");
		} finally {
			if (ctx.isActive()) {
				SlowDeleteController.releaseDelete.set(true);
				ctx.close();
			}
		}

		// Second pending file was intentionally stopped before processing; verify restart picks it up and clears it cleanly
		SlowDeleteController.blockOnFirstDelete = false;
		DesktopServer.Running restarted = DesktopServer.start(home, 0, true, Map.<String, String>of());
		try {
			PendingAssetCleanupRepository repo = restarted.context().getBean(PendingAssetCleanupRepository.class);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (repo.count() > 0 && System.nanoTime() < deadline) {
				Thread.sleep(50);
			}
			assertEquals(0, repo.count(), "remaining pending_asset_cleanup entry must resume and clear on restart");
			assertEquals(0, restarted.context().getBean(JdbcTemplate.class)
					.queryForObject("SELECT COUNT(*) FROM pending_asset_cleanup", Integer.class));
		} finally {
			restarted.context().close();
		}
	}

	static final class SlowDeleteController {
		static volatile boolean blockOnFirstDelete;
		static CountDownLatch enteredDelete = new CountDownLatch(1);
		static final AtomicBoolean releaseDelete = new AtomicBoolean(false);
		static final AtomicBoolean armedFirst = new AtomicBoolean(true);

		static void reset() {
			blockOnFirstDelete = false;
			enteredDelete = new CountDownLatch(1);
			releaseDelete.set(false);
			armedFirst.set(true);
		}
	}

	@TestConfiguration
	static class SlowDeleteConfig {
		@Bean
		@Primary
		FileStorage slowDeleteFileStorage(FileStorageService delegate) {
			return new FileStorage() {
				@Override
				public StoredFile store(String originalFilename, InputStream source) {
					return delegate.store(originalFilename, source);
				}

				@Override
				public void delete(String storageKey) {
					if (SlowDeleteController.blockOnFirstDelete && SlowDeleteController.armedFirst.compareAndSet(true, false)) {
						SlowDeleteController.enteredDelete.countDown();
						long maxWait = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
						while (!SlowDeleteController.releaseDelete.get() && System.nanoTime() < maxWait) {
							try {
								Thread.sleep(50);
							} catch (InterruptedException ignored) {
								// Deliberately ignore interrupts just like non-interruptible native I/O
							}
						}
					}
					delegate.delete(storageKey);
				}

				@Override
				public Resource load(String storageKey) {
					return delegate.load(storageKey);
				}

				@Override
				public String calculateSha256(String storageKey) {
					return delegate.calculateSha256(storageKey);
				}
			};
		}
	}
}
