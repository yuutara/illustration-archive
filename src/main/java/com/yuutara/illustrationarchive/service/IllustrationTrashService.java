package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.desktop.MaintenanceGate;
import com.yuutara.illustrationarchive.dto.TrashItem;
import com.yuutara.illustrationarchive.dto.TrashMoveResult;
import com.yuutara.illustrationarchive.dto.TrashPage;
import com.yuutara.illustrationarchive.dto.TrashPurgeResult;
import com.yuutara.illustrationarchive.dto.TrashRestoreResult;
import com.yuutara.illustrationarchive.repository.PendingAssetCleanupRepository;
import com.yuutara.illustrationarchive.repository.PendingAssetCleanupRepository.PendingCleanupEntry;
import com.yuutara.illustrationarchive.repository.TrashRepository;
import com.yuutara.illustrationarchive.service.IllustrationTrashDatabaseService.PurgeBatch;
import com.yuutara.illustrationarchive.storage.FileStorage;
import com.yuutara.illustrationarchive.storage.ThumbnailStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Coordinates desktop Trash operations, 30-day automatic expiration, and post-commit storage
 * cleanup retries while preserving strict separation between database transactions and file deletes.
 */
@Service
public class IllustrationTrashService {

	public static final int RETENTION_DAYS = 30;
	static final int MAX_WORKS_PER_REQUEST = 2000;
	static final int PURGE_BATCH_SIZE = 50;

	private static final Logger log = LoggerFactory.getLogger(IllustrationTrashService.class);

	private final TrashRepository trashRepository;
	private final IllustrationTrashDatabaseService databaseService;
	private final PendingAssetCleanupRepository cleanupRepository;
	private final FileStorage fileStorage;
	private final ThumbnailStorage thumbnailStorage;
	private final MaintenanceGate maintenanceGate;
	private final Clock clock;
	private final ReentrantLock cleanupLock = new ReentrantLock();

	@Autowired
	public IllustrationTrashService(
			TrashRepository trashRepository,
			IllustrationTrashDatabaseService databaseService,
			PendingAssetCleanupRepository cleanupRepository,
			FileStorage fileStorage,
			ThumbnailStorage thumbnailStorage,
			@Autowired(required = false) MaintenanceGate maintenanceGate
	) {
		this(trashRepository, databaseService, cleanupRepository, fileStorage, thumbnailStorage,
				maintenanceGate, Clock.systemUTC());
	}

	public IllustrationTrashService(
			TrashRepository trashRepository,
			IllustrationTrashDatabaseService databaseService,
			PendingAssetCleanupRepository cleanupRepository,
			FileStorage fileStorage,
			ThumbnailStorage thumbnailStorage,
			MaintenanceGate maintenanceGate,
			Clock clock
	) {
		this.trashRepository = trashRepository;
		this.databaseService = databaseService;
		this.cleanupRepository = cleanupRepository;
		this.fileStorage = fileStorage;
		this.thumbnailStorage = thumbnailStorage;
		this.maintenanceGate = maintenanceGate;
		this.clock = clock;
	}

	public void moveToTrash(long id) {
		if (id <= 0) {
			throw new IllustrationNotFoundException(id);
		}
		TrashMoveResult result = databaseService.moveToTrash(List.of(id), clock.instant());
		if (result.movedCount() == 0) {
			throw new IllustrationNotFoundException(id);
		}
	}

	public TrashMoveResult moveToTrash(List<Long> ids) {
		List<Long> normalized = normalizeWorkIds(ids);
		return databaseService.moveToTrash(normalized, clock.instant());
	}

	public TrashRestoreResult restoreFromTrash(List<Long> ids) {
		List<Long> normalized = normalizeWorkIds(ids);
		return databaseService.restoreFromTrash(normalized);
	}

	public TrashPage list(Integer requestedPage, Integer requestedSize) {
		int page = requestedPage == null ? 0 : requestedPage;
		int size = requestedSize == null ? 24 : requestedSize;
		if (page < 0) {
			throw new IllegalArgumentException("page must be greater than or equal to 0.");
		}
		if (size < 1 || size > 100) {
			throw new IllegalArgumentException("size must be between 1 and 100.");
		}
		long totalItems = trashRepository.countTrashed();
		int totalPages = Math.toIntExact(totalItems == 0 ? 0 : (totalItems - 1) / size + 1);
		List<TrashItem> items = page >= totalPages
				? List.of()
				: trashRepository.findTrashedPage(size, (long) page * size, RETENTION_DAYS);
		return new TrashPage(items, page, size, totalItems, totalPages, RETENTION_DAYS);
	}

	public long count() {
		return trashRepository.countTrashed();
	}

	public TrashPurgeResult purge(List<Long> ids) {
		List<Long> normalized = normalizeWorkIds(ids);
		PurgeBatch batch = databaseService.purgeByIds(normalized, clock.instant());
		int cleaned = drainPendingCleanups(() -> false);
		int pending = cleanupRepository.count();
		return new TrashPurgeResult(normalized.size(), batch.purgedIds().size(), batch.purgedIds(), cleaned, pending);
	}

	public TrashPurgeResult purgeAll() {
		List<Long> purgedIds = new ArrayList<>();
		for (;;) {
			PurgeBatch batch = databaseService.purgeAllBatch(PURGE_BATCH_SIZE, clock.instant());
			if (batch.purgedIds().isEmpty()) {
				break;
			}
			purgedIds.addAll(batch.purgedIds());
		}
		int cleaned = drainPendingCleanups(() -> false);
		int pending = cleanupRepository.count();
		return new TrashPurgeResult(purgedIds.size(), purgedIds.size(), List.copyOf(purgedIds), cleaned, pending);
	}

	/**
	 * Background maintenance pass for automatic 30-day expiration and retrying previously failed file cleanups.
	 * Acquires {@link MaintenanceGate} when present so it never overlaps backup, restore, or quiesce.
	 */
	public BackgroundPurgeOutcome purgeExpiredAndCleanup(BooleanSupplier shouldStop) {
		if (shouldStop.getAsBoolean()) {
			return new BackgroundPurgeOutcome(false, 0, 0, 0);
		}
		if (maintenanceGate != null && !maintenanceGate.tryEnterBackground()) {
			return new BackgroundPurgeOutcome(false, 0, 0, 0);
		}
		try {
			int purged = 0;
			int cleaned = drainPendingCleanups(shouldStop);
			Instant cutoff = clock.instant().minus(Duration.ofDays(RETENTION_DAYS));
			while (!shouldStop.getAsBoolean()) {
				PurgeBatch batch = databaseService.purgeExpiredBatch(cutoff, PURGE_BATCH_SIZE, clock.instant());
				if (batch.purgedIds().isEmpty()) {
					break;
				}
				purged += batch.purgedIds().size();
				cleaned += drainPendingCleanups(shouldStop);
			}
			return new BackgroundPurgeOutcome(true, purged, cleaned, cleanupRepository.count());
		} finally {
			if (maintenanceGate != null) {
				maintenanceGate.leaveBackground();
			}
		}
	}

	public int retryPendingCleanups() {
		return drainPendingCleanups(() -> false);
	}

	private int drainPendingCleanups(BooleanSupplier shouldStop) {
		cleanupLock.lock();
		try {
			int cleaned = 0;
			for (PendingCleanupEntry entry : cleanupRepository.findAllOrdered()) {
				if (shouldStop.getAsBoolean()) {
					break;
				}
				if (deleteFilesAndMarkCleaned(entry)) {
					cleaned++;
				}
			}
			return cleaned;
		} finally {
			cleanupLock.unlock();
		}
	}

	private boolean deleteFilesAndMarkCleaned(PendingCleanupEntry entry) {
		try {
			if (hasThumbnail(entry.mimeType())) {
				thumbnailStorage.deleteThumbnail(entry.storageKey());
			}
			fileStorage.delete(entry.storageKey());
			cleanupRepository.deleteById(entry.id());
			return true;
		} catch (RuntimeException exception) {
			log.warn("Failed to delete trashed asset file during post-commit cleanup; will retry. id={}, storageKey={}",
					entry.id(), entry.storageKey(), exception);
			return false;
		}
	}

	private static boolean hasThumbnail(String mimeType) {
		return "image/jpeg".equals(mimeType) || "image/png".equals(mimeType);
	}

	private static List<Long> normalizeWorkIds(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new IllegalArgumentException("ids must contain at least one work id.");
		}
		LinkedHashSet<Long> unique = new LinkedHashSet<>();
		for (Long id : ids) {
			if (id == null || id <= 0) {
				throw new IllegalArgumentException("ids must contain only positive work ids.");
			}
			unique.add(id);
			if (unique.size() > MAX_WORKS_PER_REQUEST) {
				throw new IllegalArgumentException("Cannot operate on more than " + MAX_WORKS_PER_REQUEST + " works at once.");
			}
		}
		return List.copyOf(unique);
	}

	public record BackgroundPurgeOutcome(boolean executed, int purgedWorks, int cleanedFiles, int pendingFileCleanups) {
	}
}
