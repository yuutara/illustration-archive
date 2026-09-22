package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AssetContentInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetRepositoryTest {

	@Test
	void insertsAssetWithSha256() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AssetRepository repository = new AssetRepository(jdbcTemplate);
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		String sha256 = "a".repeat(64);
		when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
		when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(invocation -> {
			PreparedStatementCreator creator = invocation.getArgument(0);
			KeyHolder keyHolder = invocation.getArgument(1);
			creator.createPreparedStatement(connection);
			keyHolder.getKeyList().add(Map.of("id", 20L));
			return 1;
		});

		long assetId = repository.insert(10L, "original.jpg", "2026-09/example.jpg", "image/jpeg", 1234L, 0, sha256);

		assertEquals(20L, assetId);
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(connection).prepareStatement(sqlCaptor.capture(), eq(Statement.RETURN_GENERATED_KEYS));
		assertTrue(sqlCaptor.getValue().contains("sha256"));
		verify(statement).setString(7, sha256);
	}

	@Test
	void propagatesDuplicateKeyExceptionFromAssetInsert() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AssetRepository repository = new AssetRepository(jdbcTemplate);
		String sha256 = "a".repeat(64);
		DuplicateKeyException duplicateKeyException = new DuplicateKeyException("duplicate key");
		when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
				.thenThrow(duplicateKeyException);

		assertThrows(
				DuplicateKeyException.class,
				() -> repository.insert(10L, "original.jpg", "2026-09/example.jpg", "image/jpeg", 1234L, 0, sha256)
		);
	}

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

	@Test
	void findsStorageKeysByIllustrationIdInStableOrder() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AssetRepository repository = new AssetRepository(jdbcTemplate);
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getString("storage_key")).thenReturn("2026-09/example.jpg");

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<String>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(10L))).thenReturn(List.of());

		repository.findStorageKeysByIllustrationId(10L);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq(10L));
		String sql = sqlCaptor.getValue();
		assertTrue(sql.contains("SELECT storage_key"));
		assertTrue(sql.contains("FROM asset"));
		assertTrue(sql.contains("WHERE illustration_id = ?"));
		assertTrue(sql.contains("ORDER BY sort_order ASC, id ASC"));
		assertEquals("2026-09/example.jpg", rowMapperCaptor.getValue().mapRow(resultSet, 0));
	}

	@Test
	void findsAssetIdBySha256() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AssetRepository repository = new AssetRepository(jdbcTemplate);
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getLong("id")).thenReturn(20L);
		String sha256 = "a".repeat(64);

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<Long>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(sha256))).thenReturn(List.of(20L));

		var result = repository.findIdBySha256(sha256);

		assertEquals(Optional.of(20L), result);
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq(sha256));
		String sql = sqlCaptor.getValue();
		assertTrue(sql.contains("SELECT id"));
		assertTrue(sql.contains("FROM asset"));
		assertTrue(sql.contains("WHERE sha256 = ?"));
		assertEquals(20L, rowMapperCaptor.getValue().mapRow(resultSet, 0));
	}

	@Test
	void returnsEmptyWhenSha256DoesNotExist() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AssetRepository repository = new AssetRepository(jdbcTemplate);
		String sha256 = "b".repeat(64);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(sha256))).thenReturn(List.of());

		var result = repository.findIdBySha256(sha256);

		assertTrue(result.isEmpty());
		verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(sha256));
	}

	@Test
	void findsIllustrationIdBySha256() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AssetRepository repository = new AssetRepository(jdbcTemplate);
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getLong("illustration_id")).thenReturn(10L);
		String sha256 = "e".repeat(64);

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<RowMapper<Long>> rowMapperCaptor =
				(ArgumentCaptor) ArgumentCaptor.forClass(RowMapper.class);
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(sha256))).thenReturn(List.of(10L));

		assertEquals(Optional.of(10L), repository.findIllustrationIdBySha256(sha256));
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).query(sqlCaptor.capture(), rowMapperCaptor.capture(), eq(sha256));
		assertTrue(sqlCaptor.getValue().contains("SELECT illustration_id"));
		assertTrue(sqlCaptor.getValue().contains("WHERE sha256 = ?"));
		assertEquals(10L, rowMapperCaptor.getValue().mapRow(resultSet, 0));
	}

	@Test
	void updatesSha256ForAssetThatHasNoHash() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AssetRepository repository = new AssetRepository(jdbcTemplate);
		String sha256 = "c".repeat(64);

		repository.updateSha256(20L, sha256);

		verify(jdbcTemplate).update("UPDATE asset SET sha256 = ? WHERE id = ?", sha256, 20L);
	}

	@Test
	void propagatesDuplicateSha256ConstraintViolation() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		AssetRepository repository = new AssetRepository(jdbcTemplate);
		String sha256 = "d".repeat(64);
		doThrow(new DuplicateKeyException("duplicate sha256"))
				.when(jdbcTemplate).update(anyString(), any(Object[].class));

		assertThrows(DuplicateKeyException.class, () -> repository.updateSha256(21L, sha256));
	}
}
