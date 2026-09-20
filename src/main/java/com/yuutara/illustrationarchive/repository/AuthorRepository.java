package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AuthorDetail;
import com.yuutara.illustrationarchive.dto.AuthorSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class AuthorRepository {

	private static final String INSERT_SQL = """
			INSERT INTO author (display_name, x_username)
			VALUES (?, ?)
			""";

	private static final String FIND_BY_ID_SQL = """
			SELECT
				id,
				display_name,
				x_username,
				created_at,
				updated_at
			FROM author
			WHERE id = ?
			""";

	private static final String SEARCH_SQL = """
			SELECT
				id,
				display_name,
				x_username
			FROM author
			WHERE display_name LIKE ? OR x_username LIKE ?
			ORDER BY display_name ASC, id ASC
			LIMIT 20
			""";

	private final JdbcTemplate jdbcTemplate;

	public AuthorRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public long insert(String displayName, String xUsername) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
			statement.setString(1, displayName);
			statement.setString(2, xUsername);
			return statement;
		}, keyHolder);

		Number generatedKey = keyHolder.getKey();
		if (generatedKey == null) {
			throw new IllegalStateException("Failed to obtain generated id for author insert.");
		}
		return generatedKey.longValue();
	}

	public Optional<AuthorDetail> findById(long id) {
		return jdbcTemplate.query(FIND_BY_ID_SQL, (resultSet, rowNum) -> new AuthorDetail(
				resultSet.getLong("id"),
				resultSet.getString("display_name"),
				resultSet.getString("x_username"),
				resultSet.getTimestamp("created_at").toLocalDateTime(),
				resultSet.getTimestamp("updated_at").toLocalDateTime()
		), id).stream().findFirst();
	}

	public List<AuthorSummary> search(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return List.of();
		}

		String pattern = "%" + keyword + "%";
		return jdbcTemplate.query(SEARCH_SQL, (resultSet, rowNum) -> new AuthorSummary(
				resultSet.getLong("id"),
				resultSet.getString("display_name"),
				resultSet.getString("x_username")
		), pattern, pattern);
	}
}
