package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.TagSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TagRepository {

	private static final String INSERT_SQL = """
			INSERT INTO tag (name)
			VALUES (?)
			""";

	private static final String FIND_BY_ID_SQL = """
			SELECT id, name
			FROM tag
			WHERE id = ?
			""";

	private static final String SEARCH_SQL = """
			SELECT id, name
			FROM tag
			WHERE name LIKE ?
			ORDER BY name ASC, id ASC
			LIMIT ? OFFSET ?
			""";

	// Name the key column: H2 also returns defaulted columns (created_at) for RETURN_GENERATED_KEYS.
	private static final String[] GENERATED_ID = {"id"};

	private final JdbcTemplate jdbcTemplate;

	public TagRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public long insert(String name) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(INSERT_SQL, GENERATED_ID);
			statement.setString(1, name);
			return statement;
		}, keyHolder);

		Number generatedKey = keyHolder.getKey();
		if (generatedKey == null) {
			throw new IllegalStateException("Failed to obtain generated id for tag insert.");
		}
		return generatedKey.longValue();
	}

	public Optional<TagSummary> findById(long id) {
		return jdbcTemplate.query(FIND_BY_ID_SQL, (resultSet, rowNum) -> new TagSummary(
				resultSet.getLong("id"),
				resultSet.getString("name")
		), id).stream().findFirst();
	}

	public List<TagSummary> search(String keyword, int limit, int offset) {
		String pattern = "%" + (keyword == null ? "" : keyword.trim()) + "%";
		return jdbcTemplate.query(SEARCH_SQL, (resultSet, rowNum) -> new TagSummary(
				resultSet.getLong("id"),
				resultSet.getString("name")
		), pattern, limit, offset);
	}

    public List<TagSummary> findSummariesByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbcTemplate.query("SELECT id, name FROM tag WHERE id IN ("
                + String.join(", ", java.util.Collections.nCopies(ids.size(), "?")) + ") ORDER BY id",
                (rs, row) -> new TagSummary(rs.getLong("id"), rs.getString("name")), ids.toArray());
    }
}
