package com.yuutara.illustrationarchive.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IllustrationTagRepositoryTest {

	@Test
	void deletesAllTagsForIllustrationWithBoundId() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationTagRepository repository = new IllustrationTagRepository(jdbcTemplate);

		repository.deleteByIllustrationId(10L);

		assertUpdate(
				jdbcTemplate,
				"DELETE FROM illustration_tag WHERE illustration_id = ?",
				10L
		);
	}

	@Test
	void insertsIllustrationTagWithIllustrationIdThenTagId() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationTagRepository repository = new IllustrationTagRepository(jdbcTemplate);

		repository.insert(10L, 3L);

		assertUpdate(
				jdbcTemplate,
				"INSERT INTO illustration_tag (illustration_id, tag_id)",
				10L,
				3L
		);
	}

	private void assertUpdate(JdbcTemplate jdbcTemplate, String expectedSql, Object... expectedParameters) {
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object[]> parametersCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).update(sqlCaptor.capture(), parametersCaptor.capture());
		assertTrue(sqlCaptor.getValue().contains(expectedSql));
		assertArrayEquals(expectedParameters, parametersCaptor.getValue());
	}
}
