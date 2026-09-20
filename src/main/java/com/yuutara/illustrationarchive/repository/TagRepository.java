package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.TagSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
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
			LIMIT 20
			""";

	private final JdbcTemplate jdbcTemplate;

	public TagRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public long insert(String name) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
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

	public List<TagSummary> search(String keyword) {
		if (keyword == null || keyword.trim().isEmpty()) {
			return List.of();
		}

		String pattern = "%" + keyword.trim() + "%";
		return jdbcTemplate.query(SEARCH_SQL, (resultSet, rowNum) -> new TagSummary(
				resultSet.getLong("id"),
				resultSet.getString("name")
		), pattern);
	}
}
