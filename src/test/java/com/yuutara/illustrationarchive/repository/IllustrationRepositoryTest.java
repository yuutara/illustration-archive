package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.IllustrationGalleryItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IllustrationRepositoryTest {

	@Test
	void countsIllustrations() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate);
		when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM illustration", Long.class)).thenReturn(7L);

		long count = repository.count();

		assertEquals(7L, count);
		verify(jdbcTemplate).queryForObject("SELECT COUNT(*) FROM illustration", Long.class);
	}

	@Test
	void mapsGalleryRowWithoutAuthor() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate);
		ResultSet resultSet = mock(ResultSet.class);
		LocalDateTime createdAt = LocalDateTime.of(2026, 9, 18, 10, 30);
		when(resultSet.getObject("author_id", Long.class)).thenReturn(null);
		when(resultSet.getLong("illustration_id")).thenReturn(10L);
		when(resultSet.getString("illustration_title")).thenReturn("Untitled");
		when(resultSet.getObject("cover_asset_id", Long.class)).thenReturn(20L);
		when(resultSet.getInt("asset_count")).thenReturn(2);
		when(resultSet.getTimestamp("illustration_created_at")).thenReturn(Timestamp.valueOf(createdAt));

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<IllustrationGalleryItem>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(24), eq(48L))).thenReturn(List.of());

		repository.findGalleryPage(24, 48L);

		verify(jdbcTemplate).query(anyString(), rowMapperCaptor.capture(), eq(24), eq(48L));
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(24), eq(48L));
		String sql = sqlCaptor.getValue();
		assertTrue(sql.contains("LEFT JOIN author a ON i.author_id = a.id"));
		assertTrue(sql.contains("ORDER BY cover_asset.sort_order ASC, cover_asset.id ASC"));
		assertTrue(sql.contains("ORDER BY i.created_at DESC, i.id DESC"));
		assertTrue(sql.contains("LIMIT ? OFFSET ?"));
		IllustrationGalleryItem item = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals(10L, item.id());
		assertEquals("Untitled", item.title());
		assertNull(item.author());
		assertEquals(20L, item.coverAssetId());
		assertEquals(2, item.assetCount());
		assertEquals(createdAt, item.createdAt());
	}

	@Test
	void mapsGalleryRowWithAuthor() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate);
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getObject("author_id", Long.class)).thenReturn(3L);
		when(resultSet.getString("author_display_name")).thenReturn("Artist");
		when(resultSet.getString("author_x_username")).thenReturn("artist_x");
		when(resultSet.getLong("illustration_id")).thenReturn(10L);
		when(resultSet.getString("illustration_title")).thenReturn(null);
		when(resultSet.getObject("cover_asset_id", Long.class)).thenReturn(null);
		when(resultSet.getInt("asset_count")).thenReturn(0);
		when(resultSet.getTimestamp("illustration_created_at"))
				.thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 9, 18, 11, 0)));

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<IllustrationGalleryItem>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(10), eq(0L))).thenReturn(List.of());

		repository.findGalleryPage(10, 0L);

		verify(jdbcTemplate).query(anyString(), rowMapperCaptor.capture(), eq(10), eq(0L));
		IllustrationGalleryItem item = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals(3L, item.author().id());
		assertEquals("Artist", item.author().displayName());
		assertEquals("artist_x", item.author().xUsername());
		assertNull(item.title());
		assertNull(item.coverAssetId());
		assertEquals(0, item.assetCount());
	}
}
