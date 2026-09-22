package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AssetContentInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class AssetRepository {

	private static final String INSERT_SQL = """
			INSERT INTO asset (
				illustration_id,
				original_filename,
				storage_key,
				mime_type,
				file_size,
				sort_order,
				sha256
			)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String FIND_CONTENT_INFO_BY_ID_SQL = """
			SELECT
				storage_key,
				mime_type,
				file_size
			FROM asset
			WHERE id = ?
			""";

	private static final String FIND_STORAGE_KEYS_BY_ILLUSTRATION_ID_SQL = """
			SELECT storage_key
			FROM asset
			WHERE illustration_id = ?
			ORDER BY sort_order ASC, id ASC
			""";

	private static final String FIND_ID_BY_SHA256_SQL = """
			SELECT id
			FROM asset
			WHERE sha256 = ?
			""";

	private static final String FIND_ILLUSTRATION_ID_BY_SHA256_SQL = """
			SELECT illustration_id
			FROM asset
			WHERE sha256 = ?
			""";

	private static final String FIND_SHA256_BACKFILL_CANDIDATES_SQL = """
			SELECT id, storage_key
			FROM asset
			WHERE sha256 IS NULL
			ORDER BY id ASC
			""";

	private static final String UPDATE_SHA256_SQL =
			"UPDATE asset SET sha256 = ? WHERE id = ?";

	private final JdbcTemplate jdbcTemplate;

	public AssetRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public long insert(
			long illustrationId,
			String originalFilename,
			String storageKey,
			String mimeType,
			long fileSize,
			int sortOrder
	) {
		return insert(illustrationId, originalFilename, storageKey, mimeType, fileSize, sortOrder, null);
	}

	public long insert(
			long illustrationId,
			String originalFilename,
			String storageKey,
			String mimeType,
			long fileSize,
			int sortOrder,
			String sha256
	) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, illustrationId);
			statement.setString(2, originalFilename);
			statement.setString(3, storageKey);
			statement.setString(4, mimeType);
			statement.setLong(5, fileSize);
			statement.setInt(6, sortOrder);
			statement.setString(7, sha256);
			return statement;
		}, keyHolder);

		Number generatedKey = keyHolder.getKey();
		if (generatedKey == null) {
			throw new IllegalStateException("Failed to obtain generated id for asset insert.");
		}
		return generatedKey.longValue();
	}

	public Optional<AssetContentInfo> findContentInfoById(long id) {
		return jdbcTemplate.query(FIND_CONTENT_INFO_BY_ID_SQL, (resultSet, rowNum) -> new AssetContentInfo(
				resultSet.getString("storage_key"),
				resultSet.getString("mime_type"),
				resultSet.getLong("file_size")
		), id).stream().findFirst();
	}

	public List<String> findStorageKeysByIllustrationId(long illustrationId) {
		return jdbcTemplate.query(
				FIND_STORAGE_KEYS_BY_ILLUSTRATION_ID_SQL,
				(resultSet, rowNum) -> resultSet.getString("storage_key"),
				illustrationId
		);
	}

	public Optional<Long> findIdBySha256(String sha256) {
		return jdbcTemplate.query(
				FIND_ID_BY_SHA256_SQL,
				(resultSet, rowNum) -> resultSet.getLong("id"),
				sha256
		).stream().findFirst();
	}

	public Optional<Long> findIllustrationIdBySha256(String sha256) {
		return jdbcTemplate.query(
				FIND_ILLUSTRATION_ID_BY_SHA256_SQL,
				(resultSet, rowNum) -> resultSet.getLong("illustration_id"),
				sha256
		).stream().findFirst();
	}

	public List<AssetSha256BackfillCandidate> findSha256BackfillCandidates() {
		return jdbcTemplate.query(
				FIND_SHA256_BACKFILL_CANDIDATES_SQL,
				(resultSet, rowNum) -> new AssetSha256BackfillCandidate(
						resultSet.getLong("id"),
						resultSet.getString("storage_key")
				)
		);
	}

	public void updateSha256(long assetId, String sha256) {
		jdbcTemplate.update(UPDATE_SHA256_SQL, sha256, assetId);
	}
}
