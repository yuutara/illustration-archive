package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AuthorDetail;
import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthorServiceTest {
    @Test
    void manualCreateReusesUniqueNormalizedHandleAndNeverChangesItsProfile() {
        var repository = mock(AuthorRepository.class);
        var service = new AuthorService(repository);
        var detail = new AuthorDetail(15L, "Existing profile", "artist", null, null);
        when(repository.findIdsByNormalizedXUsername("Artist")).thenReturn(List.of(15L));
        when(repository.findById(15L)).thenReturn(Optional.of(detail));
        assertEquals(detail, service.create("Different name", " @Artist "));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ambiguousHandlesAreNotGuessedFromDisplayNames() {
        var repository = mock(AuthorRepository.class);
        var service = new AuthorService(repository);
        when(repository.findIdsByNormalizedXUsername("artist")).thenReturn(List.of(1L, 2L));
        assertThrows(IllegalArgumentException.class, () -> service.create("Same name", "artist"));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

	@Test
	void createsAuthorWithTrimmedValuesAndReturnsDatabaseDetail() {
		AuthorRepository repository = mock(AuthorRepository.class);
		AuthorService service = new AuthorService(repository);
		AuthorDetail detail = new AuthorDetail(
				7L,
				"Artist",
				"artist_x",
				LocalDateTime.of(2026, 9, 20, 10, 0),
				LocalDateTime.of(2026, 9, 20, 10, 0)
		);
		when(repository.insert("Artist", "artist_x")).thenReturn(7L);
		when(repository.findById(7L)).thenReturn(Optional.of(detail));

		AuthorDetail result = service.create("  Artist  ", "  artist_x  ");

		assertEquals(detail, result);
		verify(repository).insert("Artist", "artist_x");
		verify(repository).findById(7L);
	}

	@Test
	void rejectsNullOrBlankDisplayName() {
		AuthorRepository repository = mock(AuthorRepository.class);
		AuthorService service = new AuthorService(repository);

		assertThrows(IllegalArgumentException.class, () -> service.create(null, null));
		assertThrows(IllegalArgumentException.class, () -> service.create("   ", null));

		verifyNoInteractions(repository);
	}

	@Test
	void convertsBlankXUsernameToNullAfterTrimming() {
		AuthorRepository repository = mock(AuthorRepository.class);
		AuthorService service = new AuthorService(repository);
		AuthorDetail detail = new AuthorDetail(
				7L, "Artist", null,
				LocalDateTime.of(2026, 9, 20, 10, 0),
				LocalDateTime.of(2026, 9, 20, 10, 0)
		);
		when(repository.insert("Artist", null)).thenReturn(7L);
		when(repository.findById(7L)).thenReturn(Optional.of(detail));

		service.create("Artist", "   ");

		verify(repository).insert("Artist", null);
	}

	@Test
	void delegatesSearchToRepository() {
		AuthorRepository repository = mock(AuthorRepository.class);
		AuthorService service = new AuthorService(repository);
		List<AuthorSummary> expected = List.of(new AuthorSummary(7L, "Artist", "artist_x"));
		when(repository.search("art", 20, 0)).thenReturn(expected);

		List<AuthorSummary> result = service.search("art", 20, 0);

		assertEquals(expected, result);
		verify(repository).search("art", 20, 0);
	}
	@Test
	void rejectsInvalidPaginationBeforeQuerying() {
		AuthorRepository repository = mock(AuthorRepository.class);
		AuthorService service = new AuthorService(repository);
		assertThrows(IllegalArgumentException.class, () -> service.search(null, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> service.search(null, 101, 0));
		assertThrows(IllegalArgumentException.class, () -> service.search(null, 20, -1));
		verifyNoInteractions(repository);
	}
}
