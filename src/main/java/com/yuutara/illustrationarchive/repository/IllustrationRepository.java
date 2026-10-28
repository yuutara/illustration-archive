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

import java.util.ArrayList;
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

	private static final String DELETE_BY_ID_SQL = "DELETE FROM illustration WHERE id = ?";

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

	// Name the key column: H2 also returns defaulted columns (created_at) for RETURN_GENERATED_KEYS.
	private static final String[] GENERATED_ID = {"id"};

	private final JdbcTemplate jdbcTemplate;
	// Gallery list reads only. Both run on the same DataSource and join the caller's Spring transaction.
	private final IllustrationGalleryMapper galleryMapper;

	public IllustrationRepository(JdbcTemplate jdbcTemplate, IllustrationGalleryMapper galleryMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.galleryMapper = galleryMapper;
	}

	public long insert() {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> connection.prepareStatement(INSERT_SQL, GENERATED_ID), keyHolder);

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
					GENERATED_ID);
			statement.setLong(1, authorId);
			statement.setString(2, sourceUrl);
			return statement;
		}, keys);
		Number id = keys.getKey();
		if (id == null) throw new IllegalStateException("Failed to obtain X illustration id.");
		return id.longValue();
	}

	public long count(IllustrationGalleryQuery query) {
		return galleryMapper.count(query.authorIds(), query.tagIds(), likePattern(query.q()));
	}

	public List<IllustrationGalleryItem> findGalleryPage(IllustrationGalleryQuery query, int size, long offset) {
		return galleryMapper.findPage(query.authorIds(), query.tagIds(), likePattern(query.q()), size, offset);
	}

	/** Works without assets are absent from the map; callers default to an empty list. */
	public Map<Long, List<GalleryAsset>> findGalleryAssetsByIllustrationIds(List<Long> illustrationIds) {
		if (illustrationIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<GalleryAsset>> assets = new LinkedHashMap<>();
		for (IllustrationGalleryMapper.AssetRow row : galleryMapper.findAssets(illustrationIds)) {
			assets.computeIfAbsent(row.illustrationId(), ignored -> new ArrayList<>())
					.add(new GalleryAsset(row.id(), row.mimeType(), row.sortOrder()));
		}
		return assets;
	}

	// The keyword is matched literally: '!' is the ESCAPE character in the mapper's LIKE clauses.
	private static String likePattern(String q) {
		return q == null ? null : "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
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
