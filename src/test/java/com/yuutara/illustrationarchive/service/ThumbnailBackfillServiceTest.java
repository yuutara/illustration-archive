package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.AssetThumbnailBackfillCandidate;
import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThumbnailBackfillServiceTest {

	@TempDir
	Path storageRoot;

	@Test
	void recordsSingleItemFailureAndContinuesInIdOrder() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		ThumbnailBackfillService service = new ThumbnailBackfillService(assetRepository, thumbnailService);
		AssetThumbnailBackfillCandidate first = new AssetThumbnailBackfillCandidate(
				10L, "2026-08/missing.jpg", "image/jpeg"
		);
		AssetThumbnailBackfillCandidate gif = new AssetThumbnailBackfillCandidate(
				15L, "2026-08/animated.gif", "image/gif"
		);
		AssetThumbnailBackfillCandidate second = new AssetThumbnailBackfillCandidate(
				20L, "2026-09/ready.png", "image/png"
		);
		RuntimeException failure = new FileStorageException("Stored image could not be decoded.");
		when(assetRepository.findThumbnailBackfillCandidates()).thenReturn(List.of(first, gif, second));
		when(thumbnailService.generateThumbnail(first.storageKey())).thenThrow(failure);

		List<ThumbnailBackfillResult> results = service.backfillThumbnails();

		assertEquals(3, results.size());
		assertEquals(ThumbnailBackfillResult.failure(first.assetId(), first.storageKey(), failure.getMessage()), results.get(0));
		assertEquals(ThumbnailBackfillResult.skippedGif(gif.assetId(), gif.storageKey()), results.get(1));
		assertEquals(ThumbnailBackfillResult.ready(second.assetId(), second.storageKey()), results.get(2));
		var order = inOrder(assetRepository, thumbnailService);
		order.verify(assetRepository).findThumbnailBackfillCandidates();
		order.verify(thumbnailService).generateThumbnail(first.storageKey());
		order.verify(thumbnailService).generateThumbnail(second.storageKey());
		verify(thumbnailService, org.mockito.Mockito.never()).generateThumbnail(gif.storageKey());
	}

	@Test
	void surfacesUnexpectedCandidateQueryFailure() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		ThumbnailBackfillService service = new ThumbnailBackfillService(assetRepository, thumbnailService);
		IllegalStateException queryFailure = new IllegalStateException("database unavailable");
		when(assetRepository.findThumbnailBackfillCandidates()).thenThrow(queryFailure);

		assertSame(queryFailure, assertThrows(IllegalStateException.class, service::backfillThumbnails));
	}

	@Test
	void repeatedBackfillReusesExistingThumbnail() throws IOException {
		AssetRepository assetRepository = mock(AssetRepository.class);
		AssetThumbnailBackfillCandidate candidate = new AssetThumbnailBackfillCandidate(
				10L, "2026-09/small.png", "image/png"
		);
		when(assetRepository.findThumbnailBackfillCandidates()).thenReturn(List.of(candidate));
		FileStorageService fileStorageService = new FileStorageService(storageRoot.toString());
		ThumbnailService thumbnailService = new ThumbnailService(fileStorageService);
		ThumbnailBackfillService service = new ThumbnailBackfillService(assetRepository, thumbnailService);
		Path originalPath = storageRoot.resolve(candidate.storageKey());
		Files.createDirectories(originalPath.getParent());
		assertTrue(ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", originalPath.toFile()));

		List<ThumbnailBackfillResult> firstRun = service.backfillThumbnails();
		Path thumbnailPath = storageRoot.resolve("thumbnails").resolve(candidate.storageKey());
		byte[] existingThumbnail = "existing thumbnail bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Files.write(thumbnailPath, existingThumbnail);

		List<ThumbnailBackfillResult> secondRun = service.backfillThumbnails();

		assertEquals(List.of(ThumbnailBackfillResult.ready(candidate.assetId(), candidate.storageKey())), firstRun);
		assertEquals(firstRun, secondRun);
		assertTrue(Files.isRegularFile(thumbnailPath));
		assertTrue(Files.isRegularFile(originalPath));
		assertArrayEquals(existingThumbnail, Files.readAllBytes(thumbnailPath));
		verify(assetRepository, org.mockito.Mockito.times(2)).findThumbnailBackfillCandidates();
	}
}
