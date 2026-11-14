package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.TrashMoveResult;
import com.yuutara.illustrationarchive.dto.TrashRestoreResult;
import com.yuutara.illustrationarchive.repository.PendingAssetCleanupRepository;
import com.yuutara.illustrationarchive.repository.PendingAssetCleanupRepository.AssetFile;
import com.yuutara.illustrationarchive.repository.PendingAssetCleanupRepository.PendingCleanupEntry;
import com.yuutara.illustrationarchive.repository.TrashRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Transactional database boundary for desktop Trash state transitions. Permanent deletion validates
 * row state ({@code trashed_at IS NOT NULL}) under a row lock, records pending file cleanup keys in the
 * same transaction, and deletes the database rows before returning to post-commit storage cleanup.
 */
@Service
public class IllustrationTrashDatabaseService {

	private static final int SQL_CHUNK_SIZE = 500;

	private final TrashRepository trashRepository;
	private final PendingAssetCleanupRepository cleanupRepository;

	public IllustrationTrashDatabaseService(TrashRepository trashRepository,
			PendingAssetCleanupRepository cleanupRepository) {
		this.trashRepository = trashRepository;
		this.cleanupRepository = cleanupRepository;
	}

	@Transactional
	public TrashMoveResult moveToTrash(List<Long> ids, Instant trashedAt) {
		List<Long> ordered = sortDistinct(ids);
		List<Long> movedIds = new ArrayList<>();
		for (List<Long> chunk : chunks(ordered)) {
			List<Long> locked = trashRepository.lockActiveIds(chunk);
			if (!locked.isEmpty()) {
				trashRepository.markTrashed(locked, trashedAt);
				movedIds.addAll(locked);
			}
		}
		return new TrashMoveResult(ordered.size(), movedIds.size(), List.copyOf(movedIds));
	}

	@Transactional
	public TrashRestoreResult restoreFromTrash(List<Long> ids) {
		List<Long> ordered = sortDistinct(ids);
		List<Long> restoredIds = new ArrayList<>();
		for (List<Long> chunk : chunks(ordered)) {
			List<Long> locked = trashRepository.lockTrashedIds(chunk);
			if (!locked.isEmpty()) {
				trashRepository.clearTrashed(locked);
				restoredIds.addAll(locked);
			}
		}
		return new TrashRestoreResult(ordered.size(), restoredIds.size(), List.copyOf(restoredIds));
	}

	@Transactional
	public PurgeBatch purgeByIds(List<Long> ids, Instant now) {
		List<Long> ordered = sortDistinct(ids);
		List<Long> purgedIds = new ArrayList<>();
		List<PendingCleanupEntry> entries = new ArrayList<>();
		for (List<Long> chunk : chunks(ordered)) {
			PurgeBatch part = purgeLockedIds(trashRepository.lockTrashedIds(chunk), now);
			purgedIds.addAll(part.purgedIds());
			entries.addAll(part.cleanupEntries());
		}
		return new PurgeBatch(List.copyOf(purgedIds), List.copyOf(entries));
	}

	@Transactional
	public PurgeBatch purgeExpiredBatch(Instant cutoff, int limit, Instant now) {
		return purgeLockedIds(trashRepository.lockExpiredTrashedIds(cutoff, limit), now);
	}

	@Transactional
	public PurgeBatch purgeAllBatch(int limit, Instant now) {
		return purgeLockedIds(trashRepository.lockAllTrashedIds(limit), now);
	}

	private PurgeBatch purgeLockedIds(List<Long> lockedIds, Instant now) {
		if (lockedIds.isEmpty()) {
			return new PurgeBatch(List.of(), List.of());
		}
		List<AssetFile> files = trashRepository.findAssetFilesByIllustrationIds(lockedIds);
		List<PendingCleanupEntry> cleanupEntries = cleanupRepository.enqueueAll(files, now);
		trashRepository.deleteTrashedByIds(lockedIds);
		return new PurgeBatch(List.copyOf(lockedIds), cleanupEntries);
	}

	private static List<Long> sortDistinct(List<Long> ids) {
		return ids.stream().distinct().sorted().toList();
	}

	private static List<List<Long>> chunks(List<Long> ids) {
		List<List<Long>> parts = new ArrayList<>();
		for (int i = 0; i < ids.size(); i += SQL_CHUNK_SIZE) {
			parts.add(ids.subList(i, Math.min(i + SQL_CHUNK_SIZE, ids.size())));
		}
		return parts;
	}

	public record PurgeBatch(List<Long> purgedIds, List<PendingCleanupEntry> cleanupEntries) {
	}
}
