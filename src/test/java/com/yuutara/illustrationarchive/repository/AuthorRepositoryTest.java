package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AuthorDetail;
import com.yuutara.illustrationarchive.dto.AuthorSummary;
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
import java.sql.Timestamp;
import java.time.LocalDateTime;
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

class AuthorRepositoryTest {

	@Test
	void insertsAuthorWithBoundParametersAndReturnsGeneratedId() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AuthorRepository repository = new AuthorRepository(jdbcTemplate);
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
		when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(invocation -> {
			PreparedStatementCreator creator = invocation.getArgument(0);
			KeyHolder keyHolder = invocation.getArgument(1);
			creator.createPreparedStatement(connection);
			keyHolder.getKeyList().add(Map.of("id", 7L));
			return 1;
		});

		long id = repository.insert("Artist", "artist_x");

		assertEquals(7L, id);
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(connection).prepareStatement(sqlCaptor.capture(), eq(Statement.RETURN_GENERATED_KEYS));
		assertTrue(sqlCaptor.getValue().contains("INSERT INTO author (display_name, x_username)"));
		verify(statement).setString(1, "Artist");
		verify(statement).setString(2, "artist_x");
	}

	@Test
	void findsAndMapsAuthorById() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AuthorRepository repository = new AuthorRepository(jdbcTemplate);
		ResultSet resultSet = mock(ResultSet.class);
		LocalDateTime createdAt = LocalDateTime.of(2026, 9, 20, 10, 0);
		LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 20, 11, 0);
		when(resultSet.getLong("id")).thenReturn(7L);
		when(resultSet.getString("display_name")).thenReturn("Artist");
		when(resultSet.getString("x_username")).thenReturn("artist_x");
		when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(createdAt));
		when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.valueOf(updatedAt));

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<AuthorDetail>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(7L))).thenReturn(List.of());

		repository.findById(7L);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq(7L));
		assertTrue(sqlCaptor.getValue().contains("FROM author"));
		assertTrue(sqlCaptor.getValue().contains("WHERE id = ?"));
		AuthorDetail author = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals(7L, author.id());
		assertEquals("Artist", author.displayName());
		assertEquals("artist_x", author.xUsername());
		assertEquals(createdAt, author.createdAt());
		assertEquals(updatedAt, author.updatedAt());
	}

	@Test
	void returnsEmptyWhenAuthorDoesNotExist() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AuthorRepository repository = new AuthorRepository(jdbcTemplate);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(99L))).thenReturn(List.of());

		var result = repository.findById(99L);

		assertTrue(result.isEmpty());
		verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(99L));
	}

	@Test
	void searchesByDisplayNameOrXUsernameWithStableOrderingAndLimit() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AuthorRepository repository = new AuthorRepository(jdbcTemplate);
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getLong("id")).thenReturn(7L);
		when(resultSet.getString("display_name")).thenReturn("Artist");
		when(resultSet.getString("x_username")).thenReturn("artist_x");

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<AuthorSummary>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("%art%"), eq("%art%"))).thenReturn(List.of());

		repository.search("art");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq("%art%"), eq("%art%"));
		String sql = sqlCaptor.getValue();
		assertTrue(sql.contains("display_name LIKE ? OR x_username LIKE ?"));
		assertTrue(sql.contains("ORDER BY display_name ASC, id ASC"));
		assertTrue(sql.contains("LIMIT 20"));
		AuthorSummary author = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals(7L, author.id());
		assertEquals("Artist", author.displayName());
		assertEquals("artist_x", author.xUsername());
	}

	@Test
	void returnsEmptyWithoutQueryingWhenSearchKeywordIsNullOrBlank() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AuthorRepository repository = new AuthorRepository(jdbcTemplate);

		assertEquals(List.of(), repository.search(null));
		assertEquals(List.of(), repository.search("   "));

		verifyNoInteractions(jdbcTemplate);
	}
}
