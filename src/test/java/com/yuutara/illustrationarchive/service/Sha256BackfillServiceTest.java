package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.AssetSha256BackfillCandidate;
import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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
	void calculatesCandidatesInOrderWithoutUpdatingOrResavingFiles() {
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

		List<Sha256BackfillResult> results = service.calculatePendingHashes();

		assertEquals(List.of(
				Sha256BackfillResult.success(first.assetId(), first.storageKey(), firstSha256),
				Sha256BackfillResult.success(second.assetId(), second.storageKey(), secondSha256)
		), results);
		var inOrder = inOrder(fileStorageService);
		inOrder.verify(fileStorageService).calculateSha256(first.storageKey());
		inOrder.verify(fileStorageService).calculateSha256(second.storageKey());
		verify(assetRepository, never()).updateSha256(anyLong(), anyString());
		verify(fileStorageService, never()).store(any(MultipartFile.class));
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

		List<Sha256BackfillResult> results = service.calculatePendingHashes();

		assertEquals(2, results.size());
		assertEquals(missing.assetId(), results.get(0).assetId());
		assertEquals(missing.storageKey(), results.get(0).storageKey());
		assertNull(results.get(0).sha256());
		assertEquals("Stored file does not exist or is not a regular file.", results.get(0).errorMessage());
		assertEquals(Sha256BackfillResult.success(existing.assetId(), existing.storageKey(), existingSha256), results.get(1));
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

		List<Sha256BackfillResult> results = service.calculatePendingHashes();

		assertEquals("Storage key must stay within the configured storage root.", results.get(0).errorMessage());
		assertEquals(validSha256, results.get(1).sha256());
		verify(fileStorageService).calculateSha256(valid.storageKey());
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
				service::calculatePendingHashes
		);

		assertSame(unexpectedException, thrown);
	}
}
