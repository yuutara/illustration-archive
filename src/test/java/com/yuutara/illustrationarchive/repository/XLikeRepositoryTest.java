package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class XLikeRepositoryTest {
	@Test
	void insertsStringIdsAndUtcPostTimeAndReturnsGeneratedId() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
		when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(call -> {
			PreparedStatementCreator creator = call.getArgument(0);
			KeyHolder keys = call.getArgument(1);
			creator.createPreparedStatement(connection);
			keys.getKeyList().add(Map.of("id", 7L));
			return 1;
		});
		XLikeCandidate candidate = new XLikeCandidate("999999999999999999999999", "888888888888888888888888",
				"user", "Artist", "text", Instant.parse("2026-09-25T09:00:00Z"),
				XLikeStatus.PENDING, List.of());

		assertEquals(7L, new XLikeRepository(jdbc).insertIfAbsent(candidate));

		verify(statement).setString(1, candidate.xPostId());
		verify(statement).setString(2, candidate.xAuthorId());
		verify(statement).setObject(6, LocalDateTime.of(2026, 9, 25, 9, 0));
		verify(statement).setString(7, "PENDING");
	}

	@Test
	void duplicatePostDoesNotOverwriteStoredStatus() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
				.thenThrow(new DuplicateKeyException("duplicate"));
		XLikeCandidate candidate = new XLikeCandidate("11", "a", "user", "Artist", null,
				null, XLikeStatus.PENDING, List.of());

		assertNull(new XLikeRepository(jdbc).insertIfAbsent(candidate));
	}

	@Test
	void pendingQueryFiltersStatusAndOrdersNewestFirst() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		XLikeRepository repository = new XLikeRepository(jdbc);
		@SuppressWarnings({"rawtypes", "unchecked"})
		ArgumentCaptor<RowMapper<XLikeInboxItem>> mapper = (ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

		repository.findPending();

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).query(sql.capture(), mapper.capture());
		assertTrue(sql.getValue().contains("WHERE status = 'PENDING'"));
		assertTrue(sql.getValue().contains("ORDER BY post_created_at DESC, id DESC"));
		ResultSet rs = mock(ResultSet.class);
		when(rs.getLong("id")).thenReturn(7L);
		when(rs.getString("x_post_id")).thenReturn("11");
		when(rs.getObject("post_created_at", LocalDateTime.class))
				.thenReturn(LocalDateTime.of(2026, 9, 25, 9, 0));
		XLikeInboxItem item = mapper.getValue().mapRow(rs, 0);
		assertEquals(Instant.parse("2026-09-25T09:00:00Z"), item.postCreatedAt());
	}
}
