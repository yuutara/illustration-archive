package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AssetSummary;
import com.yuutara.illustrationarchive.dto.TagSummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
	void mapsDetailBaseWithoutAuthor() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate, mock(IllustrationGalleryMapper.class));
		ResultSet resultSet = mock(ResultSet.class);
		LocalDateTime createdAt = LocalDateTime.of(2026, 9, 19, 10, 0);
		LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 19, 11, 0);
		when(resultSet.getObject("author_id", Long.class)).thenReturn(null);
		when(resultSet.getLong("illustration_id")).thenReturn(10L);
		when(resultSet.getString("illustration_title")).thenReturn("Example");
		when(resultSet.getString("illustration_source_url")).thenReturn("https://example.com/source");
		when(resultSet.getString("illustration_note")).thenReturn("Note");
		when(resultSet.getTimestamp("illustration_created_at")).thenReturn(Timestamp.valueOf(createdAt));
		when(resultSet.getTimestamp("illustration_updated_at")).thenReturn(Timestamp.valueOf(updatedAt));

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<IllustrationDetailBase>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(10L))).thenReturn(List.of());

		repository.findDetailBaseById(10L);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq(10L));
		String sql = sqlCaptor.getValue();
		assertTrue(sql.contains("LEFT JOIN author a ON i.author_id = a.id"));
		assertTrue(sql.contains("WHERE i.id = ?"));
		IllustrationDetailBase base = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals(10L, base.id());
		assertEquals("Example", base.title());
		assertNull(base.author());
		assertEquals("https://example.com/source", base.sourceUrl());
		assertEquals("Note", base.note());
		assertEquals(createdAt, base.createdAt());
		assertEquals(updatedAt, base.updatedAt());
	}

	@Test
	void mapsDetailBaseWithAuthor() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate, mock(IllustrationGalleryMapper.class));
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getObject("author_id", Long.class)).thenReturn(3L);
		when(resultSet.getString("author_display_name")).thenReturn("Artist");
		when(resultSet.getString("author_x_username")).thenReturn("artist_x");
		when(resultSet.getLong("illustration_id")).thenReturn(10L);
		when(resultSet.getTimestamp("illustration_created_at"))
				.thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 9, 19, 10, 0)));
		when(resultSet.getTimestamp("illustration_updated_at"))
				.thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 9, 19, 11, 0)));

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<IllustrationDetailBase>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(10L))).thenReturn(List.of());

		repository.findDetailBaseById(10L);

		verify(jdbcTemplate).query(anyString(), rowMapperCaptor.capture(), eq(10L));
		IllustrationDetailBase base = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals(3L, base.author().id());
		assertEquals("Artist", base.author().displayName());
		assertEquals("artist_x", base.author().xUsername());
	}

	@Test
	void returnsEmptyWhenDetailBaseDoesNotExist() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate, mock(IllustrationGalleryMapper.class));
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(99L))).thenReturn(List.of());

		var result = repository.findDetailBaseById(99L);

		assertTrue(result.isEmpty());
		verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(99L));
	}

	@Test
	void mapsAssetSummariesWithRequiredFieldsAndSortOrder() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate, mock(IllustrationGalleryMapper.class));
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getLong("id")).thenReturn(20L);
		when(resultSet.getString("original_filename")).thenReturn("original.jpg");
		when(resultSet.getString("mime_type")).thenReturn("image/jpeg");
		when(resultSet.getLong("file_size")).thenReturn(123L);
		when(resultSet.getInt("sort_order")).thenReturn(0);

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<AssetSummary>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(10L))).thenReturn(List.of());

		repository.findAssetSummariesByIllustrationId(10L);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq(10L));
		String sql = sqlCaptor.getValue();
		assertTrue(sql.contains("original_filename"));
		assertTrue(sql.contains("mime_type"));
		assertTrue(sql.contains("file_size"));
		assertTrue(sql.contains("ORDER BY sort_order ASC, id ASC"));
		assertFalse(sql.contains("storage_key"));
		AssetSummary asset = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals(20L, asset.id());
		assertEquals("original.jpg", asset.originalFilename());
		assertEquals("image/jpeg", asset.mimeType());
		assertEquals(123L, asset.fileSize());
		assertEquals(0, asset.sortOrder());
	}

	@Test
	void mapsTagSummariesUsingJoinAndNameOrdering() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate, mock(IllustrationGalleryMapper.class));
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getLong("tag_id")).thenReturn(5L);
		when(resultSet.getString("tag_name")).thenReturn("landscape");

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<TagSummary>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(10L))).thenReturn(List.of());

		repository.findTagSummariesByIllustrationId(10L);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq(10L));
		String sql = sqlCaptor.getValue();
		assertTrue(sql.contains("FROM illustration_tag it"));
		assertTrue(sql.contains("JOIN tag t ON it.tag_id = t.id"));
		assertTrue(sql.contains("WHERE it.illustration_id = ?"));
		assertTrue(sql.contains("ORDER BY t.name ASC, t.id ASC"));
		TagSummary tag = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals(5L, tag.id());
		assertEquals("landscape", tag.name());
	}

	@Test
	void deletesIllustrationById() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		IllustrationRepository repository = new IllustrationRepository(jdbcTemplate, mock(IllustrationGalleryMapper.class));

		repository.deleteById(10L);

		verify(jdbcTemplate).update("DELETE FROM illustration WHERE id = ?", 10L);
	}
}
