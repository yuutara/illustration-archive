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
			LIMIT ? OFFSET ?
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

	public Optional<Long> findIdByXUserId(String xUserId) {
		return jdbcTemplate.query("SELECT id FROM author WHERE x_user_id = ? FOR UPDATE",
				(rs, row) -> rs.getLong("id"), xUserId).stream().findFirst();
	}

    private static final String NORMALIZED_HANDLE = "LOWER(TRIM(LEADING '@' FROM TRIM(x_username)))";

    public List<Long> findIdsByNormalizedXUsername(String username) {
        return jdbcTemplate.query("SELECT id FROM author WHERE " + NORMALIZED_HANDLE + " = LOWER(?) ORDER BY id FOR UPDATE",
                (rs, row) -> rs.getLong("id"), username);
    }

    // Called inside the existing per-Post transaction. Never infer identity from display names.
    public Optional<Long> claimLegacyXAuthor(String xUserId, String displayName, String username) {
        var matches = jdbcTemplate.query("SELECT id, x_user_id FROM author WHERE " + NORMALIZED_HANDLE
                + " = LOWER(?) ORDER BY id FOR UPDATE", (rs, row) -> new XIdentity(rs.getLong("id"), rs.getString("x_user_id")), username);
        if (matches.size() != 1 || matches.get(0).userId() != null) return Optional.empty();
        long id = matches.get(0).id();
        Long conflicts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM x_like_item
                WHERE LOWER(TRIM(LEADING '@' FROM TRIM(author_username))) = LOWER(?) AND x_author_id <> ?
                """, Long.class, username, xUserId);
        Long unprovenWorks = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM illustration i WHERE i.author_id = ? AND NOT EXISTS (
                    SELECT 1 FROM x_like_item x WHERE x.x_author_id = ? AND (
                        x.imported_illustration_id = i.id OR
                        i.source_url = CONCAT('https://x.com/', x.author_username, '/status/', x.x_post_id) OR
                        i.source_url = CONCAT('https://twitter.com/', x.author_username, '/status/', x.x_post_id)))
                """, Long.class, id, xUserId);
        if (conflicts == null || conflicts > 0 || unprovenWorks == null || unprovenWorks > 0) return Optional.empty();
        int changed = jdbcTemplate.update("UPDATE author SET x_user_id = ?, display_name = ?, x_username = ? WHERE id = ? AND x_user_id IS NULL",
                xUserId, displayName, username, id);
        return changed == 1 ? Optional.of(id) : Optional.empty();
    }

    private record XIdentity(long id, String userId) {}

    public List<AuthorSummary> findSummariesByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbcTemplate.query("SELECT id, display_name, x_username FROM author WHERE id IN ("
                + String.join(", ", java.util.Collections.nCopies(ids.size(), "?")) + ") ORDER BY id",
                (rs, row) -> new AuthorSummary(rs.getLong("id"), rs.getString("display_name"), rs.getString("x_username")), ids.toArray());
    }

	public long insertXAuthor(String xUserId, String displayName, String username) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
					"INSERT INTO author (x_user_id, display_name, x_username) VALUES (?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			statement.setString(1, xUserId);
			statement.setString(2, displayName);
			statement.setString(3, username);
			return statement;
		}, keys);
		Number id = keys.getKey();
		if (id == null) throw new IllegalStateException("Failed to obtain X author id.");
		return id.longValue();
	}

	public void updateXAuthor(long id, String displayName, String username) {
		jdbcTemplate.update("UPDATE author SET display_name = ?, x_username = ? WHERE id = ?",
				displayName, username, id);
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

	public List<AuthorSummary> search(String keyword, int limit, int offset) {
		String pattern = "%" + (keyword == null ? "" : keyword.trim()) + "%";
		String handlePattern = keyword != null && keyword.trim().startsWith("@")
				? "%" + keyword.trim().substring(1) + "%" : pattern;
		return jdbcTemplate.query(SEARCH_SQL, (resultSet, rowNum) -> new AuthorSummary(
				resultSet.getLong("id"),
				resultSet.getString("display_name"),
				resultSet.getString("x_username")
		), pattern, handlePattern, limit, offset);
	}
}
