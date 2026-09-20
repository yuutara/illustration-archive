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
		when(repository.search("art")).thenReturn(expected);

		List<AuthorSummary> result = service.search("art");

		assertEquals(expected, result);
		verify(repository).search("art");
	}
}
