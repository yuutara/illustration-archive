package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AssetContentInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetRepositoryTest {

	@Test
	void findsAndMapsAssetContentInfoById() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AssetRepository repository = new AssetRepository(jdbcTemplate);
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getString("storage_key")).thenReturn("2026-09/example.jpg");
		when(resultSet.getString("mime_type")).thenReturn("image/jpeg");
		when(resultSet.getLong("file_size")).thenReturn(123L);

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<AssetContentInfo>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(10L))).thenReturn(List.of());

		repository.findContentInfoById(10L);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq(10L));
		String sql = sqlCaptor.getValue();
		assertTrue(sql.contains("storage_key"));
		assertTrue(sql.contains("mime_type"));
		assertTrue(sql.contains("file_size"));
		assertTrue(sql.contains("FROM asset"));
		assertTrue(sql.contains("WHERE id = ?"));

		AssetContentInfo contentInfo = rowMapperCaptor.getValue().mapRow(resultSet, 0);
		assertEquals("2026-09/example.jpg", contentInfo.storageKey());
		assertEquals("image/jpeg", contentInfo.mimeType());
		assertEquals(123L, contentInfo.fileSize());
	}

	@Test
	void returnsEmptyWhenAssetContentInfoDoesNotExist() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AssetRepository repository = new AssetRepository(jdbcTemplate);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(99L))).thenReturn(List.of());

		var result = repository.findContentInfoById(99L);

		assertTrue(result.isEmpty());
		verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(99L));
	}
}
