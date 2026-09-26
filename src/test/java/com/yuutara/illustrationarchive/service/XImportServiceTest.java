package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.StoredFile;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class XImportServiceTest {
	private XLikeRepository items;
	private XLikeMediaRepository media;
	private XPhotoDownloadService downloader;
	private XPostPersistenceService persistence;
	private AssetRepository assets;
	private FileStorageService storage;
	private ThumbnailService thumbnails;
	private XImportService service;

	@BeforeEach
	void setup() {
		items = mock(XLikeRepository.class);
		media = mock(XLikeMediaRepository.class);
		downloader = mock(XPhotoDownloadService.class);
		persistence = mock(XPostPersistenceService.class);
		assets = mock(AssetRepository.class);
		storage = mock(FileStorageService.class);
		thumbnails = mock(ThumbnailService.class);
		service = new XImportService(items, media, downloader, persistence, assets, storage, thumbnails);
	}

	@Test
	void secondDownloadFailureDeletesEarlierFileAndNeverStartsPersistence() {
		pending(1, 2);
		when(downloader.download(photo(0))).thenReturn(file(0));
		when(downloader.download(photo(1))).thenThrow(new XPhotoDownloadException("download failed"));
		var result = service.importSelected(List.of(1L));
		assertEquals(1, result.failureCount());
		verify(storage).delete("file0");
		verifyNoInteractions(persistence);
	}

	@Test
	void persistenceFailureCleansAllDownloadedFilesAndPreservesOriginalFailure() {
		pending(1, 3);
		for (int i = 0; i < 3; i++) when(downloader.download(photo(i))).thenReturn(file(i));
		when(persistence.persist(any(), anyList(), anyList())).thenThrow(new IllegalStateException("db failed"));
		doThrow(new IllegalStateException("cleanup failed")).when(storage).delete("file1");
		var result = service.importSelected(List.of(1L));
		assertEquals("db failed", result.items().get(0).reason());
		assertEquals(1, result.failureCount());
		for (int i = 0; i < 3; i++) verify(storage).delete("file" + i);
	}

	@Test
	void duplicateCleansWholePostAndKeepsPendingForRetry() {
		pending(1, 3);
		for (int i = 0; i < 3; i++) when(downloader.download(photo(i))).thenReturn(file(i));
		when(persistence.persist(any(), anyList(), anyList()))
				.thenThrow(new XPostDuplicateException("duplicate photo"));
		var result = service.importSelected(List.of(1L));
		assertEquals(1, result.duplicateCount());
		assertEquals("DUPLICATE", result.items().get(0).status());
		for (int i = 0; i < 3; i++) verify(storage).delete("file" + i);
		verify(items, never()).markImported(anyLong(), anyLong());
	}

	@Test
	void thumbnailFailureDoesNotUndoCommittedArchive() {
		pending(1, 1);
		when(downloader.download(photo(0))).thenReturn(file(0));
		when(persistence.persist(any(), anyList(), anyList())).thenReturn(20L);
		when(thumbnails.generateThumbnail("file0")).thenThrow(new IllegalStateException("thumbnail failed"));
		var result = service.importSelected(List.of(1L));
		assertEquals(1, result.successCount());
		assertEquals(20L, result.items().get(0).illustrationId());
		verify(storage, never()).delete(anyString());
	}

	@Test
	void nonPendingItemsNeverDownload() {
		for (long id = 1; id <= 3; id++) {
			XLikeStatus status = List.of(XLikeStatus.SKIPPED, XLikeStatus.IMPORTED, XLikeStatus.UNSUPPORTED).get((int) id - 1);
			when(items.findForImport(id, false)).thenReturn(Optional.of(item(id, status)));
		}
		var result = service.importSelected(List.of(1L, 2L, 3L));
		assertEquals(3, result.failureCount());
		verifyNoInteractions(downloader, persistence);
	}

	@Test
	void batchContinuesAfterDuplicateAndReturnsPerItemResults() {
		for (long id = 1; id <= 3; id++) pending(id, 1);
		when(downloader.download(photo(0))).thenReturn(file(0));
		when(persistence.persist(any(), anyList(), anyList()))
				.thenReturn(21L).thenThrow(new XPostDuplicateException("duplicate")).thenReturn(23L);
		var result = service.importSelected(List.of(1L, 2L, 3L));
		assertEquals(3, result.total());
		assertEquals(2, result.successCount());
		assertEquals(1, result.duplicateCount());
		assertEquals(23L, result.items().get(2).illustrationId());
		verify(storage, times(1)).delete("file0");
	}

	private void pending(long id, int count) {
		when(items.findForImport(id, false)).thenReturn(Optional.of(item(id, XLikeStatus.PENDING)));
		when(media.findByItemId(id)).thenReturn(java.util.stream.IntStream.range(0, count).mapToObj(this::photo).toList());
	}

	private XLikeRepository.ImportItem item(long id, XLikeStatus status) {
		return new XLikeRepository.ImportItem(id, "post" + id, "user", "artist", "Artist", status);
	}

	private XLikeMedia photo(int index) {
		return new XLikeMedia("media" + index, index, "photo", "https://pbs.twimg.com/media/" + index + ".jpg", 100, 100);
	}

	private StoredFile file(int index) {
		return new StoredFile(index + ".jpg", "file" + index, "image/jpeg", 100, "sha" + index);
	}
}
