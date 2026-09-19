package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.List;

@Repository
public class IllustrationRepository {

	private static final String INSERT_SQL = """
			INSERT INTO illustration (title, author_id, source_url, note)
			VALUES (NULL, NULL, NULL, NULL)
			""";

	private static final String COUNT_SQL = "SELECT COUNT(*) FROM illustration";

	private static final String FIND_GALLERY_PAGE_SQL = """
			SELECT
				i.id AS illustration_id,
				i.title AS illustration_title,
				a.id AS author_id,
				a.display_name AS author_display_name,
				a.x_username AS author_x_username,
				(
					SELECT cover_asset.id
					FROM asset cover_asset
					WHERE cover_asset.illustration_id = i.id
					ORDER BY cover_asset.sort_order ASC, cover_asset.id ASC
					LIMIT 1
				) AS cover_asset_id,
				(
					SELECT COUNT(*)
					FROM asset asset_count
					WHERE asset_count.illustration_id = i.id
				) AS asset_count,
				i.created_at AS illustration_created_at
			FROM illustration i
			LEFT JOIN author a ON i.author_id = a.id
			ORDER BY i.created_at DESC, i.id DESC
			LIMIT ? OFFSET ?
			""";

	private final JdbcTemplate jdbcTemplate;

	public IllustrationRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public long insert() {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS), keyHolder);

		Number generatedKey = keyHolder.getKey();
		if (generatedKey == null) {
			throw new IllegalStateException("Failed to obtain generated id for illustration insert.");
		}
		return generatedKey.longValue();
	}

	public long count() {
		Long total = jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
		if (total == null) {
			throw new IllegalStateException("Failed to obtain illustration count.");
		}
		return total;
	}

	public List<IllustrationGalleryItem> findGalleryPage(int size, long offset) {
		return jdbcTemplate.query(FIND_GALLERY_PAGE_SQL, (resultSet, rowNum) -> {
			Long authorId = resultSet.getObject("author_id", Long.class);
			AuthorSummary author = authorId == null
					? null
					: new AuthorSummary(
							authorId,
							resultSet.getString("author_display_name"),
							resultSet.getString("author_x_username")
					);

			return new IllustrationGalleryItem(
					resultSet.getLong("illustration_id"),
					resultSet.getString("illustration_title"),
					author,
					resultSet.getObject("cover_asset_id", Long.class),
					resultSet.getInt("asset_count"),
					resultSet.getTimestamp("illustration_created_at").toLocalDateTime()
			);
		}, size, offset);
	}
}
