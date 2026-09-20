package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IllustrationMetadataUpdateRepositoryTest {

	@Test
	void updatesOnlyTitleWhenOnlyTitleIsPresent() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTitle("New title");
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

		repository.updateBasicMetadata(10L, request);

		assertUpdate(jdbcTemplate, "UPDATE illustration SET title = ? WHERE id = ?", "New title", 10L);
	}

	@Test
	void updatesTitleAndNoteInFieldOrderWhenBothArePresent() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTitle("New title");
		request.setNote("New note");
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

		repository.updateBasicMetadata(10L, request);

		assertUpdate(
				jdbcTemplate,
				"UPDATE illustration SET title = ?, note = ? WHERE id = ?",
				"New title",
				"New note",
				10L
		);
	}

	@Test
	void updatesOnlyAuthorIdWhenOnlyAuthorIdIsPresent() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setAuthorId(7L);
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

		repository.updateBasicMetadata(10L, request);

		assertUpdate(jdbcTemplate, "UPDATE illustration SET author_id = ? WHERE id = ?", 7L, 10L);
	}

	@Test
	void bindsNullWhenAuthorIdIsExplicitlyCleared() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setAuthorId(null);
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

		repository.updateBasicMetadata(10L, request);

		assertUpdate(jdbcTemplate, "UPDATE illustration SET author_id = ? WHERE id = ?", null, 10L);
	}

	@Test
	void updatesTitleAndAuthorIdInStableColumnOrder() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTitle("New title");
		request.setAuthorId(7L);
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

		repository.updateBasicMetadata(10L, request);

		assertUpdate(
				jdbcTemplate,
				"UPDATE illustration SET title = ?, author_id = ? WHERE id = ?",
				"New title",
				7L,
				10L
		);
	}

	@Test
	void bindsNullWhenSourceUrlIsExplicitlyCleared() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setSourceUrl(null);
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

		repository.updateBasicMetadata(10L, request);

		assertUpdate(jdbcTemplate, "UPDATE illustration SET source_url = ? WHERE id = ?", null, 10L);
	}

	@Test
	void doesNotExecuteUpdateWhenNoFieldsArePresent() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate);

		repository.updateBasicMetadata(10L, new IllustrationPatchRequest());

		verifyNoInteractions(jdbcTemplate);
	}

	private void assertUpdate(JdbcTemplate jdbcTemplate, String expectedSql, Object... expectedParameters) {
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object[]> parametersCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).update(sqlCaptor.capture(), parametersCaptor.capture());
		assertEquals(expectedSql, sqlCaptor.getValue());
		assertArrayEquals(expectedParameters, parametersCaptor.getValue());
	}
}
