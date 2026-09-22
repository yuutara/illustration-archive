package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.AssetSha256BackfillCandidate;
import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Sha256BackfillServiceTest {

	@Test
	void updatesCandidatesInOrderWithoutResavingFiles() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		Sha256BackfillService service = new Sha256BackfillService(assetRepository, fileStorageService);
		AssetSha256BackfillCandidate first = new AssetSha256BackfillCandidate(10L, "2026-08/first.jpg");
		AssetSha256BackfillCandidate second = new AssetSha256BackfillCandidate(20L, "2026-09/second.jpg");
		String firstSha256 = "a".repeat(64);
		String secondSha256 = "b".repeat(64);

		when(assetRepository.findSha256BackfillCandidates()).thenReturn(List.of(first, second));
		when(fileStorageService.calculateSha256(first.storageKey())).thenReturn(firstSha256);
		when(fileStorageService.calculateSha256(second.storageKey())).thenReturn(secondSha256);
		when(assetRepository.findIdBySha256(firstSha256)).thenReturn(Optional.empty());
		when(assetRepository.findIdBySha256(secondSha256)).thenReturn(Optional.empty());

		List<Sha256BackfillResult> results = service.backfillPendingSha256();

		assertEquals(List.of(
				Sha256BackfillResult.updated(first.assetId(), first.storageKey(), firstSha256),
				Sha256BackfillResult.updated(second.assetId(), second.storageKey(), secondSha256)
		), results);
		var inOrder = inOrder(fileStorageService);
		inOrder.verify(fileStorageService).calculateSha256(first.storageKey());
		inOrder.verify(fileStorageService).calculateSha256(second.storageKey());
		verify(assetRepository).updateSha256(first.assetId(), firstSha256);
		verify(assetRepository).updateSha256(second.assetId(), secondSha256);
		verify(fileStorageService, never()).store(any(MultipartFile.class));
	}

	@Test
	void recordsExistingHashAsDuplicateWithoutUpdating() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		Sha256BackfillService service = new Sha256BackfillService(assetRepository, fileStorageService);
		AssetSha256BackfillCandidate candidate = new AssetSha256BackfillCandidate(10L, "2026-08/duplicate.jpg");
		String sha256 = "a".repeat(64);

		when(assetRepository.findSha256BackfillCandidates()).thenReturn(List.of(candidate));
		when(fileStorageService.calculateSha256(candidate.storageKey())).thenReturn(sha256);
		when(assetRepository.findIdBySha256(sha256)).thenReturn(Optional.of(20L));

		List<Sha256BackfillResult> results = service.backfillPendingSha256();

		assertEquals(
				Sha256BackfillResult.duplicate(candidate.assetId(), candidate.storageKey(), sha256, 20L),
				results.get(0)
		);
		verify(assetRepository, never()).updateSha256(anyLong(), anyString());
	}

	@Test
	void convertsConcurrentUniqueConflictToDuplicateWhenSecondLookupFindsOtherAsset() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		Sha256BackfillService service = new Sha256BackfillService(assetRepository, fileStorageService);
		AssetSha256BackfillCandidate candidate = new AssetSha256BackfillCandidate(10L, "2026-08/concurrent.jpg");
		String sha256 = "b".repeat(64);
		DuplicateKeyException duplicateKeyException = new DuplicateKeyException("unique constraint");

		when(assetRepository.findSha256BackfillCandidates()).thenReturn(List.of(candidate));
		when(fileStorageService.calculateSha256(candidate.storageKey())).thenReturn(sha256);
		when(assetRepository.findIdBySha256(sha256)).thenReturn(Optional.empty(), Optional.of(30L));
		org.mockito.Mockito.doThrow(duplicateKeyException)
				.when(assetRepository).updateSha256(candidate.assetId(), sha256);

		List<Sha256BackfillResult> results = service.backfillPendingSha256();

		assertEquals(
				Sha256BackfillResult.duplicate(candidate.assetId(), candidate.storageKey(), sha256, 30L),
				results.get(0)
		);
	}

	@Test
	void propagatesUniqueConflictWhenSecondLookupCannotFindOtherAsset() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		Sha256BackfillService service = new Sha256BackfillService(assetRepository, fileStorageService);
		AssetSha256BackfillCandidate candidate = new AssetSha256BackfillCandidate(10L, "2026-08/unknown-conflict.jpg");
		String sha256 = "c".repeat(64);
		DuplicateKeyException duplicateKeyException = new DuplicateKeyException("unique constraint");

		when(assetRepository.findSha256BackfillCandidates()).thenReturn(List.of(candidate));
		when(fileStorageService.calculateSha256(candidate.storageKey())).thenReturn(sha256);
		when(assetRepository.findIdBySha256(sha256)).thenReturn(Optional.empty(), Optional.empty());
		org.mockito.Mockito.doThrow(duplicateKeyException)
				.when(assetRepository).updateSha256(candidate.assetId(), sha256);

		DuplicateKeyException thrown = assertThrows(
				DuplicateKeyException.class,
				service::backfillPendingSha256
		);

		assertSame(duplicateKeyException, thrown);
	}

	@Test
	void recordsMissingFileFailureAndContinuesWithFollowingCandidate() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		Sha256BackfillService service = new Sha256BackfillService(assetRepository, fileStorageService);
		AssetSha256BackfillCandidate missing = new AssetSha256BackfillCandidate(10L, "2026-08/missing.jpg");
		AssetSha256BackfillCandidate existing = new AssetSha256BackfillCandidate(20L, "2026-09/existing.jpg");
		String existingSha256 = "c".repeat(64);

		when(assetRepository.findSha256BackfillCandidates()).thenReturn(List.of(missing, existing));
		when(fileStorageService.calculateSha256(missing.storageKey()))
				.thenThrow(new FileStorageException("Stored file does not exist or is not a regular file."));
		when(fileStorageService.calculateSha256(existing.storageKey())).thenReturn(existingSha256);
		when(assetRepository.findIdBySha256(existingSha256)).thenReturn(Optional.empty());

		List<Sha256BackfillResult> results = service.backfillPendingSha256();

		assertEquals(2, results.size());
		assertEquals(missing.assetId(), results.get(0).assetId());
		assertEquals(missing.storageKey(), results.get(0).storageKey());
		assertNull(results.get(0).sha256());
		assertEquals("Stored file does not exist or is not a regular file.", results.get(0).errorMessage());
		assertEquals(Sha256BackfillResult.updated(existing.assetId(), existing.storageKey(), existingSha256), results.get(1));
		verify(assetRepository).updateSha256(existing.assetId(), existingSha256);
		verify(fileStorageService).calculateSha256(existing.storageKey());
	}

	@Test
	void recordsInvalidStorageKeyFailureAndContinues() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		Sha256BackfillService service = new Sha256BackfillService(assetRepository, fileStorageService);
		AssetSha256BackfillCandidate invalid = new AssetSha256BackfillCandidate(10L, "../outside.jpg");
		AssetSha256BackfillCandidate valid = new AssetSha256BackfillCandidate(20L, "2026-09/valid.jpg");
		String validSha256 = "d".repeat(64);

		when(assetRepository.findSha256BackfillCandidates()).thenReturn(List.of(invalid, valid));
		when(fileStorageService.calculateSha256(invalid.storageKey()))
				.thenThrow(new FileStorageValidationException("Storage key must stay within the configured storage root."));
		when(fileStorageService.calculateSha256(valid.storageKey())).thenReturn(validSha256);
		when(assetRepository.findIdBySha256(validSha256)).thenReturn(Optional.empty());

		List<Sha256BackfillResult> results = service.backfillPendingSha256();

		assertEquals("Storage key must stay within the configured storage root.", results.get(0).errorMessage());
		assertEquals(validSha256, results.get(1).sha256());
		verify(assetRepository).updateSha256(valid.assetId(), validSha256);
		verify(fileStorageService).calculateSha256(valid.storageKey());
	}

	@Test
	void processesUpdatedDuplicateAndFailedCandidatesInInputOrder() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		Sha256BackfillService service = new Sha256BackfillService(assetRepository, fileStorageService);
		AssetSha256BackfillCandidate updated = new AssetSha256BackfillCandidate(10L, "2026-08/updated.jpg");
		AssetSha256BackfillCandidate duplicate = new AssetSha256BackfillCandidate(20L, "2026-08/duplicate.jpg");
		AssetSha256BackfillCandidate failed = new AssetSha256BackfillCandidate(30L, "2026-08/missing.jpg");
		String updatedSha256 = "e".repeat(64);
		String duplicateSha256 = "f".repeat(64);

		when(assetRepository.findSha256BackfillCandidates()).thenReturn(List.of(updated, duplicate, failed));
		when(fileStorageService.calculateSha256(updated.storageKey())).thenReturn(updatedSha256);
		when(fileStorageService.calculateSha256(duplicate.storageKey())).thenReturn(duplicateSha256);
		when(fileStorageService.calculateSha256(failed.storageKey()))
				.thenThrow(new FileStorageException("Stored file does not exist or is not a regular file."));
		when(assetRepository.findIdBySha256(updatedSha256)).thenReturn(Optional.empty());
		when(assetRepository.findIdBySha256(duplicateSha256)).thenReturn(Optional.of(40L));

		List<Sha256BackfillResult> results = service.backfillPendingSha256();

		assertEquals(List.of(
				Sha256BackfillResult.updated(updated.assetId(), updated.storageKey(), updatedSha256),
				Sha256BackfillResult.duplicate(duplicate.assetId(), duplicate.storageKey(), duplicateSha256, 40L),
				Sha256BackfillResult.failure(
						failed.assetId(),
						failed.storageKey(),
						"Stored file does not exist or is not a regular file."
				)
		), results);
		verify(assetRepository).updateSha256(updated.assetId(), updatedSha256);
		verify(assetRepository, never()).updateSha256(duplicate.assetId(), duplicateSha256);
	}

	@Test
	void propagatesUnexpectedRuntimeExceptionInsteadOfRecordingItAsFileFailure() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		Sha256BackfillService service = new Sha256BackfillService(assetRepository, fileStorageService);
		AssetSha256BackfillCandidate candidate = new AssetSha256BackfillCandidate(10L, "2026-09/image.jpg");
		IllegalStateException unexpectedException = new IllegalStateException("programming error");

		when(assetRepository.findSha256BackfillCandidates()).thenReturn(List.of(candidate));
		when(fileStorageService.calculateSha256(candidate.storageKey())).thenThrow(unexpectedException);

		IllegalStateException thrown = assertThrows(
				IllegalStateException.class,
				service::backfillPendingSha256
		);

		assertSame(unexpectedException, thrown);
	}
}
