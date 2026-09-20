package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.TagSummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TagRepositoryTest {

	@Test
	void insertsTagWithBoundNameAndReturnsGeneratedId() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		TagRepository repository = new TagRepository(jdbcTemplate);
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
		when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(invocation -> {
			PreparedStatementCreator creator = invocation.getArgument(0);
			KeyHolder keyHolder = invocation.getArgument(1);
			creator.createPreparedStatement(connection);
			keyHolder.getKeyList().add(Map.of("id", 5L));
			return 1;
		});

		long id = repository.insert("landscape");

		assertEquals(5L, id);
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(connection).prepareStatement(sqlCaptor.capture(), eq(Statement.RETURN_GENERATED_KEYS));
		assertTrue(sqlCaptor.getValue().contains("INSERT INTO tag (name)"));
		verify(statement).setString(1, "landscape");
	}

	@Test
	void findsAndMapsTagById() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		TagRepository repository = new TagRepository(jdbcTemplate);
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getLong("id")).thenReturn(5L);
		when(resultSet.getString("name")).thenReturn("landscape");

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<TagSummary>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(5L))).thenReturn(List.of());

		repository.findById(5L);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq(5L));
		assertTrue(sqlCaptor.getValue().contains("SELECT id, name"));
		assertTrue(sqlCaptor.getValue().contains("FROM tag"));
		assertTrue(sqlCaptor.getValue().contains("WHERE id = ?"));
		TagSummary tag = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals(5L, tag.id());
		assertEquals("landscape", tag.name());
	}

	@Test
	void returnsEmptyWhenTagDoesNotExist() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		TagRepository repository = new TagRepository(jdbcTemplate);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(99L))).thenReturn(List.of());

		var result = repository.findById(99L);

		assertTrue(result.isEmpty());
		verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(99L));
	}

	@Test
	void searchesWithTrimmedLikeParameterStableOrderingAndLimit() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		TagRepository repository = new TagRepository(jdbcTemplate);
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getLong("id")).thenReturn(5L);
		when(resultSet.getString("name")).thenReturn("landscape");

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<TagSummary>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("%land%"))).thenReturn(List.of());

		repository.search("  land  ");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq("%land%"));
		String sql = sqlCaptor.getValue();
		assertTrue(sql.contains("WHERE name LIKE ?"));
		assertTrue(sql.contains("ORDER BY name ASC, id ASC"));
		assertTrue(sql.contains("LIMIT 20"));
		TagSummary tag = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals(5L, tag.id());
		assertEquals("landscape", tag.name());
	}

	@Test
	void returnsEmptyWithoutQueryingWhenSearchKeywordIsNullOrBlank() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		TagRepository repository = new TagRepository(jdbcTemplate);

		assertEquals(List.of(), repository.search(null));
		assertEquals(List.of(), repository.search("   "));

		verifyNoInteractions(jdbcTemplate);
	}
}
