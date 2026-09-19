package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AssetContentInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
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
				sort_order
			)
			VALUES (?, ?, ?, ?, ?, ?)
			""";

	private static final String FIND_CONTENT_INFO_BY_ID_SQL = """
			SELECT
				storage_key,
				mime_type,
				file_size
			FROM asset
			WHERE id = ?
			""";

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
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, illustrationId);
			statement.setString(2, originalFilename);
			statement.setString(3, storageKey);
			statement.setString(4, mimeType);
			statement.setLong(5, fileSize);
			statement.setInt(6, sortOrder);
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
}
