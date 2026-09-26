package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class XPostPersistenceServiceTest {
	private XLikeRepository items;
	private AuthorRepository authors;
	private IllustrationRepository illustrations;
	private AssetRepository assets;
	private XPostPersistenceService service;
	private XLikeRepository.ImportItem item;

	@BeforeEach
	void setup() {
		items = mock(XLikeRepository.class);
		authors = mock(AuthorRepository.class);
		illustrations = mock(IllustrationRepository.class);
		assets = mock(AssetRepository.class);
		service = new XPostPersistenceService(items, authors, illustrations, assets);
		item = new XLikeRepository.ImportItem(7, "99", "user-1", "artist", "Artist", XLikeStatus.PENDING);
		when(items.findForImport(7, true)).thenReturn(Optional.of(item));
		when(illustrations.insertXPost(anyLong(), anyString())).thenReturn(20L);
		when(authors.insertXAuthor(anyString(), anyString(), anyString())).thenReturn(10L);
	}

	@Test
	void singlePhotoCreatesOneIllustrationAndAssetAndMarksItem() {
		assertEquals(20L, service.persist(item, media(1), files(1)));
		verify(authors).insertXAuthor("user-1", "Artist", "artist");
		verify(illustrations).insertXPost(10L, "https://x.com/artist/status/99");
		verify(assets).insert(20L, "0.jpg", "0.jpg", "image/jpeg", 100L, 0, "sha0");
		verify(items).markImported(7, 20);
	}

	@Test
	void threePhotosKeepSortOrderWithinOneIllustration() {
		service.persist(item, media(3), files(3));
		verify(illustrations, times(1)).insertXPost(anyLong(), anyString());
		for (int i = 0; i < 3; i++) {
			verify(assets).insert(20L, i + ".jpg", i + ".jpg", "image/jpeg", 100L, i, "sha" + i);
		}
		verify(items).markImported(7, 20);
	}

	@Test
	void stableUserIdReusesAuthorAndUpdatesRenamedProfile() {
		when(authors.findIdByXUserId("user-1")).thenReturn(Optional.of(42L));
		var renamed = new XLikeRepository.ImportItem(7, "99", "user-1", "newname", "New Name", XLikeStatus.PENDING);
		when(items.findForImport(7, true)).thenReturn(Optional.of(renamed));
		service.persist(renamed, media(1), files(1));
		verify(authors, never()).insertXAuthor(anyString(), anyString(), anyString());
		verify(authors).updateXAuthor(42L, "New Name", "newname");
		verify(illustrations).insertXPost(42L, "https://x.com/newname/status/99");
	}

	@Test
	void duplicateExistingOrWithinPostPreventsAllRows() {
		when(assets.findIllustrationIdBySha256("sha1")).thenReturn(Optional.of(81L));
		assertThrows(XPostDuplicateException.class, () -> service.persist(item, media(3), files(3)));
		verifyNoInteractions(authors, illustrations);
		verify(assets, never()).insert(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyInt(), anyString());
		verify(items, never()).markImported(anyLong(), anyLong());

		List<StoredFile> repeated = List.of(files(1).get(0), new StoredFile("1.jpg", "1.jpg", "image/jpeg", 100, "sha0"));
		assertThrows(XPostDuplicateException.class, () -> service.persist(item, media(2), repeated));
	}

	@Test
	void nonPendingStatesAndLateStateChangeCannotWriteRows() {
		for (XLikeStatus status : List.of(XLikeStatus.SKIPPED, XLikeStatus.IMPORTED, XLikeStatus.UNSUPPORTED)) {
			when(items.findForImport(7, true)).thenReturn(Optional.of(new XLikeRepository.ImportItem(
					7, "99", "user-1", "artist", "Artist", status)));
			assertThrows(IllegalStateException.class, () -> service.persist(item, media(1), files(1)));
		}
		verifyNoInteractions(authors, illustrations, assets);
	}

	@Test
	void databaseFailurePropagatesBeforeInboxUpdateAndMethodIsTransactional() throws Exception {
		when(assets.insert(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyInt(), anyString()))
				.thenThrow(new IllegalStateException("db failed"));
		assertThrows(IllegalStateException.class, () -> service.persist(item, media(1), files(1)));
		verify(items, never()).markImported(anyLong(), anyLong());
		assertNotNull(XPostPersistenceService.class.getMethod("persist", XLikeRepository.ImportItem.class,
				List.class, List.class).getAnnotation(Transactional.class));
	}

	@Test
	void springProxyRollsBackOnAssetInsertFailure() {
		PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(assets.insert(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyInt(), anyString()))
				.thenThrow(new IllegalStateException("asset insert failed"));
		try (var context = new AnnotationConfigApplicationContext()) {
			context.register(TransactionConfig.class);
			context.registerBean(PlatformTransactionManager.class, () -> transactionManager);
			context.registerBean(XPostPersistenceService.class,
					() -> new XPostPersistenceService(items, authors, illustrations, assets));
			context.refresh();
			assertThrows(IllegalStateException.class,
					() -> context.getBean(XPostPersistenceService.class).persist(item, media(1), files(1)));
		}
		verify(transactionManager).rollback(any());
		verify(transactionManager, never()).commit(any());
	}

	@Configuration
	@EnableTransactionManagement
	static class TransactionConfig {
	}

	private List<XLikeMedia> media(int count) {
		return java.util.stream.IntStream.range(0, count)
				.mapToObj(i -> new XLikeMedia("m" + i, i, "photo", "https://pbs.twimg.com/media/" + i + ".jpg", 100, 100))
				.toList();
	}

	private List<StoredFile> files(int count) {
		return java.util.stream.IntStream.range(0, count)
				.mapToObj(i -> new StoredFile(i + ".jpg", i + ".jpg", "image/jpeg", 100, "sha" + i))
				.toList();
	}
}
