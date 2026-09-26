package com.yuutara.illustrationarchive.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class XLikeMediaRepositoryTest {
	@Test
	void pendingMediaQueryFiltersAndOrdersBySortPosition() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		@SuppressWarnings({"rawtypes", "unchecked"})
		ArgumentCaptor<RowMapper<XLikeMediaRepository.PendingMedia>> mapper =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbc.query(any(String.class), any(RowMapper.class))).thenReturn(List.of());

		new XLikeMediaRepository(jdbc).findForPendingItems();

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).query(sql.capture(), mapper.capture());
		assertTrue(sql.getValue().contains("WHERE i.status = 'PENDING'"));
		assertTrue(sql.getValue().contains("ORDER BY m.x_like_item_id, m.sort_order"));
		ResultSet rs = mock(ResultSet.class);
		when(rs.getLong("x_like_item_id")).thenReturn(7L);
		when(rs.getString("media_key")).thenReturn("p1");
		when(rs.getInt("sort_order")).thenReturn(0);
		when(rs.getString("media_type")).thenReturn("photo");
		when(rs.getString("source_url")).thenReturn("https://img/1");
		when(rs.getObject("width", Integer.class)).thenReturn(100);
		when(rs.getObject("height", Integer.class)).thenReturn(200);
		var row = mapper.getValue().mapRow(rs, 0);
		assertEquals(7L, row.itemId());
		assertEquals(100, row.media().width());
	}
}
