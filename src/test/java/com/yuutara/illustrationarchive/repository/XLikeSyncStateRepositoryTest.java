package com.yuutara.illustrationarchive.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class XLikeSyncStateRepositoryTest {
	@Test
	void advanceStoresTokenAndItsDigestAndClearRemovesBoth() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		XLikeSyncStateRepository state = new XLikeSyncStateRepository(jdbc);

		state.advance("opaque", 5);
		state.clear();

		byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest("opaque".getBytes(StandardCharsets.UTF_8));
		var writes = org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
				.filter(call -> call.getMethod().getName().equals("update"))
				.toList();
		assertEquals(4, writes.size());
		assertTrue(((String) writes.get(0).getArgument(0)).contains("x_like_sync_seen_token"));
		assertArrayEquals(digest, writes.get(0).getArgument(1));
		assertTrue(((String) writes.get(1).getArgument(0)).contains("x_like_sync_state"));
		assertEquals("opaque", writes.get(1).getArgument(1));
		assertEquals(Integer.valueOf(5), writes.get(1).getArgument(2));
		assertTrue(((String) writes.get(2).getArgument(0)).contains("DELETE FROM x_like_sync_state"));
		assertTrue(((String) writes.get(3).getArgument(0)).contains("DELETE FROM x_like_sync_seen_token"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void loadAndSeenQueriesReadPersistentRows() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		XLikeSyncStateRepository state = new XLikeSyncStateRepository(jdbc);
		when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
		when(jdbc.query(anyString(), any(RowMapper.class), any(byte[].class))).thenReturn(List.of(new byte[32]));

		assertTrue(state.load().isEmpty());
		assertTrue(state.hasSeen("opaque"));
		verify(jdbc).query(anyString(), any(RowMapper.class), any(byte[].class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void loadMapsSavedTokenAndPageSize() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		ResultSet row = mock(ResultSet.class);
		when(row.getString("next_token")).thenReturn("saved");
		when(row.getInt("max_results")).thenReturn(5);
		when(row.getString("status")).thenReturn("ACTIVE");
		when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call -> {
			RowMapper<XLikeSyncStateRepository.State> mapper = call.getArgument(1);
			return List.of(mapper.mapRow(row, 0));
		});

		var saved = new XLikeSyncStateRepository(jdbc).load().orElseThrow();

		assertEquals("saved", saved.nextToken());
		assertEquals(5, saved.maxResults());
		assertEquals("ACTIVE", saved.status());
	}
}
