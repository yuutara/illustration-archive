package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.controller.IllustrationDeleteController;
import com.yuutara.illustrationarchive.controller.TrashController;
import com.yuutara.illustrationarchive.desktop.DesktopServer;
import com.yuutara.illustrationarchive.desktop.MaintenanceGate;
import com.yuutara.illustrationarchive.dto.TrashPage;
import com.yuutara.illustrationarchive.dto.TrashPurgeResult;
import com.yuutara.illustrationarchive.dto.TrashRestoreResult;
import com.yuutara.illustrationarchive.repository.PendingAssetCleanupRepository;
import com.yuutara.illustrationarchive.repository.TrashRepository;
import com.yuutara.illustrationarchive.storage.FileStorage;
import com.yuutara.illustrationarchive.storage.ThumbnailStorage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies Trash service state transitions, 30-day automatic expiration boundaries, post-commit
 * pending file cleanup retries, restore-vs-purge consistency, MaintenanceGate mutual exclusion,
 * DuplicateKeyException trash detection, and server-vs-desktop controller isolation.
 */
class IllustrationTrashServiceTest {

	private DataSource dataSource;
	private JdbcTemplate jdbc;
	private MutableClock clock;
	private TrashRepository trashRepository;
	private PendingAssetCleanupRepository cleanupRepository;
	private IllustrationTrashDatabaseService databaseService;
	private FileStorage fileStorage;
	private ThumbnailStorage thumbnailStorage;

	@BeforeEach
	void setUp() {
		String url = "jdbc:h2:mem:trash-" + UUID.randomUUID() + DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_DELAY=-1";
		dataSource = new DriverManagerDataSource(url, "sa", "");
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2").load().migrate();
		jdbc = new JdbcTemplate(dataSource);
		clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
		trashRepository = new TrashRepository(jdbc);
		cleanupRepository = new PendingAssetCleanupRepository(jdbc);
		databaseService = new IllustrationTrashDatabaseService(trashRepository, cleanupRepository);
		fileStorage = mock(FileStorage.class);
		thumbnailStorage = mock(ThumbnailStorage.class);
	}

	@AfterEach
	void tearDown() {
		jdbc.execute("DROP ALL OBJECTS");
	}

	@Test
	void thirtyDayExpirationPurgesOnlyItemsAtOrBeyondRetentionBoundary() {
		seedWork(1L, "a.png", "image/png", "sha-a");
		seedWork(2L, "b.mp4", "video/mp4", "sha-b");
		seedWork(3L, "c.jpg", "image/jpeg", "sha-c");

		IllustrationTrashService service = new IllustrationTrashService(
				trashRepository, databaseService, cleanupRepository, fileStorage, thumbnailStorage,
				new MaintenanceGate(false), clock
		);
		service.moveToTrash(List.of(1L, 2L));
		clock.advance(Duration.ofHours(1));
		service.moveToTrash(3L);

		clock.set(Instant.parse("2026-10-01T00:00:00Z").plus(Duration.ofDays(29)).plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59)));
		var beforeBoundary = service.purgeExpiredAndCleanup(() -> false);
		assertTrue(beforeBoundary.executed());
		assertEquals(0, beforeBoundary.purgedWorks());
		assertEquals(3, service.count());

		clock.set(Instant.parse("2026-10-01T00:00:00Z").plus(Duration.ofDays(30)));
		var atBoundary = service.purgeExpiredAndCleanup(() -> false);
		assertTrue(atBoundary.executed());
		assertEquals(2, atBoundary.purgedWorks());
		assertEquals(2, atBoundary.cleanedFiles());
		assertEquals(0, atBoundary.pendingFileCleanups());
		assertEquals(1, service.count());
		TrashPage remaining = service.list(0, 24);
		assertEquals(List.of(3L), remaining.items().stream().map(item -> item.id()).toList());

		verify(thumbnailStorage).deleteThumbnail("a.png");
		verify(fileStorage).delete("a.png");
		verify(thumbnailStorage, never()).deleteThumbnail("b.mp4");
		verify(fileStorage).delete("b.mp4");
		verify(fileStorage, never()).delete("c.jpg");
	}

	@Test
	void fileDeleteFailureKeepsPendingQueueEntryAndRetrySucceedsPostCommit() {
		seedWork(10L, "locked.jpg", "image/jpeg", "sha-locked");
		IllustrationTrashService service = new IllustrationTrashService(
				trashRepository, databaseService, cleanupRepository, fileStorage, thumbnailStorage,
				new MaintenanceGate(false), clock
		);
		service.moveToTrash(10L);

		AtomicBoolean locked = new AtomicBoolean(true);
		doAnswer(invocation -> {
			if (locked.get()) {
				throw new IllegalStateException("file busy");
			}
			return null;
		}).when(fileStorage).delete("locked.jpg");

		TrashPurgeResult firstAttempt = service.purge(List.of(10L));
		assertEquals(1, firstAttempt.purgedCount());
		assertEquals(0, firstAttempt.cleanedFiles());
		assertEquals(1, firstAttempt.pendingFileCleanups());
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM illustration WHERE id = 10", Integer.class));
		assertEquals(1, cleanupRepository.count());

		locked.set(false);
		int retried = service.retryPendingCleanups();
		assertEquals(1, retried);
		assertEquals(0, cleanupRepository.count());
	}

	@Test
	void restoreProtectsWorkFromStalePurgeAndPurgePreventsLaterRestore() {
		seedWork(20L, "keep.png", "image/png", "sha-keep");
		seedWork(21L, "drop.png", "image/png", "sha-drop");
		IllustrationTrashService service = new IllustrationTrashService(
				trashRepository, databaseService, cleanupRepository, fileStorage, thumbnailStorage,
				new MaintenanceGate(false), clock
		);
		service.moveToTrash(List.of(20L, 21L));

		TrashRestoreResult restored = service.restoreFromTrash(List.of(20L, 20L));
		assertEquals(1, restored.requestedCount());
		assertEquals(1, restored.restoredCount());
		assertEquals(List.of(20L), restored.restoredIds());

		TrashPurgeResult purgeResult = service.purge(List.of(20L, 21L));
		assertEquals(1, purgeResult.purgedCount());
		assertEquals(List.of(21L), purgeResult.purgedIds());
		verify(fileStorage, never()).delete("keep.png");
		verify(fileStorage).delete("drop.png");

		TrashRestoreResult staleRestore = service.restoreFromTrash(List.of(21L));
		assertEquals(0, staleRestore.restoredCount());
	}

	@Test
	void closedMaintenanceGateSkipsBackgroundPurgeWithoutTouchingDatabase() {
		seedWork(30L, "old.png", "image/png", "sha-old");
		MaintenanceGate gate = new MaintenanceGate(true);
		IllustrationTrashService service = new IllustrationTrashService(
				trashRepository, databaseService, cleanupRepository, fileStorage, thumbnailStorage,
				gate, clock
		);
		service.moveToTrash(30L);
		clock.advance(Duration.ofDays(31));

		var skipped = service.purgeExpiredAndCleanup(() -> false);
		assertFalse(skipped.executed());
		assertEquals(0, skipped.purgedWorks());
		assertEquals(1, service.count());
		verify(fileStorage, never()).delete(anyString());
	}

	@Test
	void duplicateKeyRaceReportsDuplicateInTrashWhenMatchingAssetIsTrashed() throws Exception {
		var illustrations = mock(com.yuutara.illustrationarchive.repository.IllustrationRepository.class);
		var assets = mock(com.yuutara.illustrationarchive.repository.AssetRepository.class);
		when(illustrations.insert()).thenReturn(99L);
		var stored = new com.yuutara.illustrationarchive.storage.StoredFile("a.png", "a.png", "image/png", 10L, "dup-sha");
		when(assets.insert(99L, "a.png", "a.png", "image/png", 10L, 0, "dup-sha"))
				.thenThrow(new DuplicateKeyException("dup"));
		when(assets.findIllustrationIdBySha256("dup-sha")).thenReturn(java.util.Optional.of(55L));
		when(assets.isIllustrationTrashed(55L)).thenReturn(true);

		var importStorage = mock(FileStorage.class);
		when(importStorage.store(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(stored);
		when(assets.findIllustrationIdBySha256("dup-sha")).thenReturn(java.util.Optional.empty(), java.util.Optional.of(55L));
		var persistence = new IllustrationPersistenceService(illustrations, assets);
		var importService = new IllustrationImportService(importStorage, persistence, thumbnailStorage);
		MockMultipartFile upload = new MockMultipartFile("file", "a.png", "image/png", validPngBytes());
		DuplicateIllustrationException thrown = assertThrows(DuplicateIllustrationException.class,
				() -> importService.importSingle(upload));
		assertTrue(thrown.trashed());
		assertEquals(DuplicateIllustrationException.TRASHED_ERROR_CODE, thrown.errorCode());
		assertEquals(55L, thrown.illustrationId());
		verify(importStorage).delete("a.png");

		// Also verify XImportService translates DuplicateKeyException on a trashed asset into DUPLICATE with inTrash=true and deletes temp download
		var xItems = mock(com.yuutara.illustrationarchive.repository.XLikeRepository.class);
		var xMediaRepo = mock(com.yuutara.illustrationarchive.repository.XLikeMediaRepository.class);
		var xDownloader = mock(XPhotoDownloadService.class);
		var xAnimated = mock(XAnimatedDownloadService.class);
		var xAuthors = mock(com.yuutara.illustrationarchive.repository.AuthorRepository.class);
		var xStorage = mock(FileStorage.class);
		var xPersistence = new XPostPersistenceService(xItems, xAuthors, illustrations, assets);
		var xImportService = new XImportService(xItems, xMediaRepo, xDownloader, xAnimated, xPersistence, assets, xStorage, thumbnailStorage);
		var importItem = new com.yuutara.illustrationarchive.repository.XLikeRepository.ImportItem(
				7L, "999", "u1", "artist", "Artist", com.yuutara.illustrationarchive.dto.XLikeStatus.PENDING, null);
		var xMedia = List.of(new com.yuutara.illustrationarchive.dto.XLikeMedia("m0", 0, "photo", "https://pbs.twimg.com/media/0.jpg", 100, 100));
		when(xItems.findForImport(7L, false)).thenReturn(java.util.Optional.of(importItem));
		when(xItems.findForImport(7L, true)).thenReturn(java.util.Optional.of(importItem));
		when(xMediaRepo.findByItemId(7L)).thenReturn(xMedia);
		when(xDownloader.download(xMedia.getFirst())).thenReturn(stored);
		when(xAuthors.findIdByXUserId("u1")).thenReturn(java.util.Optional.of(12L));
		when(illustrations.insertXPost(12L, "https://x.com/artist/status/999", null)).thenReturn(100L);
		when(assets.findIllustrationIdBySha256("dup-sha")).thenReturn(java.util.Optional.empty(), java.util.Optional.of(55L));
		when(assets.insert(100L, "a.png", "a.png", "image/png", 10L, 0, "dup-sha"))
				.thenThrow(new DuplicateKeyException("race"));

		var xSummary = xImportService.importSelected(List.of(7L));
		assertEquals(1, xSummary.duplicateCount());
		assertEquals("DUPLICATE", xSummary.items().getFirst().status());
		assertTrue(xSummary.items().getFirst().inTrash());
		assertEquals(55L, xSummary.items().getFirst().illustrationId());
		verify(xStorage).delete("a.png");
	}

	@Test
	void deleteControllerAndTrashBeansFollowDesktopModeProperty() {
		IllustrationDeleteService deleteService = mock(IllustrationDeleteService.class);
		IllustrationTrashService trashService = mock(IllustrationTrashService.class);

		new IllustrationDeleteController(deleteService, trashService, false).delete(12L);
		verify(deleteService).delete(12L);
		verify(trashService, never()).moveToTrash(12L);

		new IllustrationDeleteController(deleteService, trashService, true).delete(12L);
		verify(trashService).moveToTrash(12L);

		ApplicationContextRunner base = new ApplicationContextRunner()
				.withBean(IllustrationTrashService.class, () -> trashService)
				.withBean(AssetContentService.class, () -> mock(AssetContentService.class))
				.withUserConfiguration(TrashController.class, TrashPurgeScheduler.class);

		base.withPropertyValues("illustration-archive.ui.desktop=false").run(ctx -> {
			assertTrue(ctx.getBeansOfType(TrashController.class).isEmpty());
			assertTrue(ctx.getBeansOfType(TrashPurgeScheduler.class).isEmpty());
		});
		base.withPropertyValues("illustration-archive.ui.desktop=true").run(ctx -> {
			assertEquals(1, ctx.getBeansOfType(TrashController.class).size());
			assertEquals(1, ctx.getBeansOfType(TrashPurgeScheduler.class).size());
		});
	}

	private static byte[] validPngBytes() throws Exception {
		var img = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
		var out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	private void seedWork(long id, String key, String mime, String sha) {
		jdbc.update("INSERT INTO illustration (id, title) VALUES (?, ?)", id, "work-" + id);
		jdbc.update("INSERT INTO asset (illustration_id, original_filename, storage_key, mime_type, file_size, sort_order, sha256) VALUES (?, ?, ?, ?, 10, 0, ?)",
				id, key, key, mime, sha);
	}

	private static final class MutableClock extends Clock {
		private Instant current;

		MutableClock(Instant initial) {
			this.current = initial;
		}

		void set(Instant instant) {
			this.current = instant;
		}

		void advance(Duration duration) {
			this.current = this.current.plus(duration);
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}
	}
}
