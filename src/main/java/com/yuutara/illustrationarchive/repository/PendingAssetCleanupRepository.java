package com.yuutara.illustrationarchive.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable queue of relative {@code storage_key} values waiting to be deleted after their owning
 * {@code illustration} rows have committed deletion.
 */
@Repository
public class PendingAssetCleanupRepository {

	private static final String[] GENERATED_ID = {"id"};

	private final JdbcTemplate jdbcTemplate;

	public PendingAssetCleanupRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<PendingCleanupEntry> enqueueAll(List<AssetFile> files, Instant createdAt) {
		if (files == null || files.isEmpty()) {
			return List.of();
		}
		LocalDateTime utc = LocalDateTime.ofInstant(createdAt.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
		List<PendingCleanupEntry> entries = new ArrayList<>(files.size());
		for (AssetFile file : files) {
			KeyHolder keyHolder = new GeneratedKeyHolder();
			jdbcTemplate.update(connection -> {
				var ps = connection.prepareStatement(
						"INSERT INTO pending_asset_cleanup (storage_key, mime_type, created_at) VALUES (?, ?, ?)",
						GENERATED_ID);
				ps.setString(1, file.storageKey());
				ps.setString(2, file.mimeType());
				ps.setObject(3, utc);
				return ps;
			}, keyHolder);
			Number key = keyHolder.getKey();
			if (key == null) {
				throw new IllegalStateException("Failed to obtain generated id for pending asset cleanup.");
			}
			entries.add(new PendingCleanupEntry(key.longValue(), file.storageKey(), file.mimeType()));
		}
		return List.copyOf(entries);
	}

	public List<PendingCleanupEntry> findAllOrdered() {
		return jdbcTemplate.query(
				"SELECT id, storage_key, mime_type FROM pending_asset_cleanup ORDER BY id ASC",
				(rs, rowNum) -> new PendingCleanupEntry(
						rs.getLong("id"),
						rs.getString("storage_key"),
						rs.getString("mime_type")
				)
		);
	}

	public int count() {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pending_asset_cleanup", Integer.class);
		return count == null ? 0 : count;
	}

	public void deleteById(long id) {
		jdbcTemplate.update("DELETE FROM pending_asset_cleanup WHERE id = ?", id);
	}

	public record AssetFile(String storageKey, String mimeType) {
	}

	public record PendingCleanupEntry(long id, String storageKey, String mimeType) {
	}
}
