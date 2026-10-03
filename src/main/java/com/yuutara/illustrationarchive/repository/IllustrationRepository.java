package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.dto.AssetSummary;
import com.yuutara.illustrationarchive.dto.GalleryAsset;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryItem;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryQuery;
import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import com.yuutara.illustrationarchive.dto.TagSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class IllustrationRepository {

	private static final String INSERT_SQL = """
			INSERT INTO illustration (title, author_id, source_url, note)
			VALUES (NULL, NULL, NULL, NULL)
			""";

	private static final String GALLERY_FROM_SQL = "FROM illustration i LEFT JOIN author a ON i.author_id = a.id ";
	private static final String DELETE_BY_ID_SQL = "DELETE FROM illustration WHERE id = ?";

	private static final String FIND_GALLERY_PAGE_SQL = """
			SELECT
				i.id AS illustration_id,
				i.title AS illustration_title,
				a.id AS author_id,
				a.display_name AS author_display_name,
				a.x_username AS author_x_username,
			cover_asset.id AS cover_asset_id,
			cover_asset.mime_type AS cover_mime_type,
				(
					SELECT COUNT(*)
					FROM asset asset_count
					WHERE asset_count.illustration_id = i.id
				) AS asset_count,
				i.created_at AS illustration_created_at
			FROM illustration i
			LEFT JOIN author a ON i.author_id = a.id
			LEFT JOIN asset cover_asset ON cover_asset.id = (
				SELECT candidate.id
				FROM asset candidate
				WHERE candidate.illustration_id = i.id
				ORDER BY candidate.sort_order ASC, candidate.id ASC
				LIMIT 1
			)
			""";

	private static final String FIND_DETAIL_BASE_BY_ID_SQL = """
			SELECT
				i.id AS illustration_id,
				i.title AS illustration_title,
				i.source_url AS illustration_source_url,
				i.note AS illustration_note,
				i.created_at AS illustration_created_at,
				i.updated_at AS illustration_updated_at,
				a.id AS author_id,
				a.display_name AS author_display_name,
				a.x_username AS author_x_username
			FROM illustration i
			LEFT JOIN author a ON i.author_id = a.id
			WHERE i.id = ?
			""";

	private static final String FIND_ASSET_SUMMARIES_BY_ILLUSTRATION_ID_SQL = """
			SELECT
				id,
				original_filename,
				mime_type,
				file_size,
				sort_order
			FROM asset
			WHERE illustration_id = ?
			ORDER BY sort_order ASC, id ASC
			""";

	private static final String FIND_TAG_SUMMARIES_BY_ILLUSTRATION_ID_SQL = """
			SELECT
				t.id AS tag_id,
				t.name AS tag_name
			FROM illustration_tag it
			JOIN tag t ON it.tag_id = t.id
			WHERE it.illustration_id = ?
			ORDER BY t.name ASC, t.id ASC
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

	public long insertXPost(long authorId, String sourceUrl) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO illustration (author_id, source_url) VALUES (?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, authorId);
			statement.setString(2, sourceUrl);
			return statement;
		}, keys);
		Number id = keys.getKey();
		if (id == null) throw new IllegalStateException("Failed to obtain X illustration id.");
		return id.longValue();
	}

	// EXISTS preserves one outer row per Illustration, even when several tags match.
	// Both count and page use this same predicate and parameter order.
	private GalleryPredicate galleryPredicate(IllustrationGalleryQuery query) {
		List<String> conditions = new ArrayList<>();
		List<Object> parameters = new ArrayList<>();
		if (!query.authorIds().isEmpty()) {
			conditions.add("i.author_id IN (" + String.join(", ", java.util.Collections.nCopies(query.authorIds().size(), "?")) + ")");
			parameters.addAll(query.authorIds());
		}
		if (!query.tagIds().isEmpty()) {
			conditions.add("EXISTS (SELECT 1 FROM illustration_tag selected_tag "
					+ "WHERE selected_tag.illustration_id = i.id AND selected_tag.tag_id IN ("
                    + String.join(", ", java.util.Collections.nCopies(query.tagIds().size(), "?")) + "))");
			parameters.addAll(query.tagIds());
		}
		if (query.q() != null) {
			String pattern = "%" + query.q().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
			conditions.add("""
					(i.title LIKE ? ESCAPE '!' OR i.note LIKE ? ESCAPE '!'
					OR a.display_name LIKE ? ESCAPE '!' OR a.x_username LIKE ? ESCAPE '!'
					OR CONCAT('@', a.x_username) LIKE ? ESCAPE '!'
					OR EXISTS (SELECT 1 FROM illustration_tag it JOIN tag t ON t.id = it.tag_id
					WHERE it.illustration_id = i.id AND t.name LIKE ? ESCAPE '!'))
					""");
			for (int index = 0; index < 6; index++) parameters.add(pattern);
		}
		return new GalleryPredicate(conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions), parameters);
	}

	private record GalleryPredicate(String sql, List<Object> parameters) {}

	public long count(IllustrationGalleryQuery query) {
		GalleryPredicate predicate = galleryPredicate(query);
		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + GALLERY_FROM_SQL + predicate.sql(),
				Long.class, predicate.parameters().toArray());
		if (total == null) {
			throw new IllegalStateException("Failed to obtain illustration count.");
		}
		return total;
	}

	public List<IllustrationGalleryItem> findGalleryPage(IllustrationGalleryQuery query, int size, long offset) {
		GalleryPredicate predicate = galleryPredicate(query);
		List<Object> parameters = new ArrayList<>(predicate.parameters());
		parameters.add(size);
		parameters.add(offset);
		String sql = FIND_GALLERY_PAGE_SQL + predicate.sql()
				+ " ORDER BY i.created_at DESC, i.id DESC LIMIT ? OFFSET ?";
		return jdbcTemplate.query(sql, (resultSet, rowNum) -> {
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
					resultSet.getString("cover_mime_type"),
					resultSet.getInt("asset_count"),
					resultSet.getTimestamp("illustration_created_at").toLocalDateTime(),
					List.of()
			);
		}, parameters.toArray());
	}

	public Map<Long, List<GalleryAsset>> findGalleryAssetsByIllustrationIds(List<Long> illustrationIds) {
		if (illustrationIds.isEmpty()) {
			return Map.of();
		}
		String placeholders = String.join(", ", Collections.nCopies(illustrationIds.size(), "?"));
		String sql = "SELECT illustration_id, id, mime_type, sort_order FROM asset "
				+ "WHERE illustration_id IN (" + placeholders + ") "
				+ "ORDER BY illustration_id, sort_order ASC, id ASC";
		Map<Long, List<GalleryAsset>> assets = new LinkedHashMap<>();
		jdbcTemplate.query(sql, resultSet -> {
			assets.computeIfAbsent(resultSet.getLong("illustration_id"), ignored -> new ArrayList<>())
					.add(new GalleryAsset(resultSet.getLong("id"), resultSet.getString("mime_type"),
							resultSet.getInt("sort_order")));
		}, illustrationIds.toArray());
		return assets;
	}

	public Optional<IllustrationDetailBase> findDetailBaseById(long id) {
		return jdbcTemplate.query(FIND_DETAIL_BASE_BY_ID_SQL, (resultSet, rowNum) -> {
			Long authorId = resultSet.getObject("author_id", Long.class);
			AuthorSummary author = authorId == null
					? null
					: new AuthorSummary(
							authorId,
							resultSet.getString("author_display_name"),
							resultSet.getString("author_x_username")
					);

			return new IllustrationDetailBase(
					resultSet.getLong("illustration_id"),
					resultSet.getString("illustration_title"),
					author,
					resultSet.getString("illustration_source_url"),
					resultSet.getString("illustration_note"),
					resultSet.getTimestamp("illustration_created_at").toLocalDateTime(),
					resultSet.getTimestamp("illustration_updated_at").toLocalDateTime()
			);
		}, id).stream().findFirst();
	}

	public List<AssetSummary> findAssetSummariesByIllustrationId(long illustrationId) {
		return jdbcTemplate.query(FIND_ASSET_SUMMARIES_BY_ILLUSTRATION_ID_SQL, (resultSet, rowNum) -> new AssetSummary(
				resultSet.getLong("id"),
				resultSet.getString("original_filename"),
				resultSet.getString("mime_type"),
				resultSet.getLong("file_size"),
				resultSet.getInt("sort_order")
		), illustrationId);
	}

	public List<TagSummary> findTagSummariesByIllustrationId(long illustrationId) {
		return jdbcTemplate.query(FIND_TAG_SUMMARIES_BY_ILLUSTRATION_ID_SQL, (resultSet, rowNum) -> new TagSummary(
				resultSet.getLong("tag_id"),
				resultSet.getString("tag_name")
		), illustrationId);
	}

	public void updateBasicMetadata(long id, IllustrationPatchRequest request) {
		List<String> assignments = new ArrayList<>();
		List<Object> parameters = new ArrayList<>();

		if (request.titlePresent()) {
			assignments.add("title = ?");
			parameters.add(request.title());
		}
		if (request.authorIdPresent()) {
			assignments.add("author_id = ?");
			parameters.add(request.authorId());
		}
		if (request.sourceUrlPresent()) {
			assignments.add("source_url = ?");
			parameters.add(request.sourceUrl());
		}
		if (request.notePresent()) {
			assignments.add("note = ?");
			parameters.add(request.note());
		}
		if (assignments.isEmpty()) {
			return;
		}

		parameters.add(id);
		String sql = "UPDATE illustration SET " + String.join(", ", assignments) + " WHERE id = ?";
		jdbcTemplate.update(sql, parameters.toArray());
	}

	public void deleteById(long id) {
		jdbcTemplate.update(DELETE_BY_ID_SQL, id);
	}
}
