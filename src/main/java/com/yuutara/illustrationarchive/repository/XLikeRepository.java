package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class XLikeRepository {
	private static final String INSERT = """
			INSERT INTO x_like_item (x_post_id, x_author_id, author_username,
			    author_display_name, post_text, post_created_at, status, discovered_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
			""";
	private static final String FIND_PENDING = """
			SELECT id, x_post_id, author_display_name, author_username, post_text, post_created_at
			FROM x_like_item
			WHERE status = 'PENDING'
			ORDER BY post_created_at DESC, id DESC
			""";

	private final JdbcTemplate jdbcTemplate;

	public XLikeRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** The unique x_post_id key is the concurrency-safe source identity boundary. */
	public Long insertIfAbsent(XLikeCandidate candidate) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		try {
			jdbcTemplate.update(connection -> {
				var statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS);
				statement.setString(1, candidate.xPostId());
				statement.setString(2, candidate.xAuthorId());
				statement.setString(3, candidate.authorUsername());
				statement.setString(4, candidate.authorDisplayName());
				statement.setString(5, candidate.postText());
				statement.setObject(6, candidate.postCreatedAt() == null ? null
						: LocalDateTime.ofInstant(candidate.postCreatedAt(), ZoneOffset.UTC));
				statement.setString(7, candidate.status().name());
				return statement;
			}, keys);
		} catch (DuplicateKeyException e) {
			// Existing status and media are deliberately left intact for future processed states.
			return null;
		}
		Number key = keys.getKey();
		if (key == null) {
			throw new IllegalStateException("Failed to obtain generated id for X Like item.");
		}
		return key.longValue();
	}

	public XLikeStatus findStatusByPostId(String postId) {
		List<XLikeStatus> statuses = jdbcTemplate.query(
				"SELECT status FROM x_like_item WHERE x_post_id = ?",
				(rs, row) -> XLikeStatus.valueOf(rs.getString("status")), postId);
		if (statuses.isEmpty()) {
			throw new IllegalStateException("Existing X Like item disappeared during sync.");
		}
		return statuses.get(0);
	}

	public int skipIfPending(long itemId) {
		return jdbcTemplate.update("""
				UPDATE x_like_item
				SET status = 'SKIPPED', updated_at = UTC_TIMESTAMP(3)
				WHERE id = ? AND status = 'PENDING'
				""", itemId);
	}

	public List<XLikeInboxItem> findPending() {
		return jdbcTemplate.query(FIND_PENDING, (rs, row) -> {
			LocalDateTime created = rs.getObject("post_created_at", LocalDateTime.class);
			Instant createdAt = created == null ? null : created.toInstant(ZoneOffset.UTC);
			return new XLikeInboxItem(rs.getLong("id"), rs.getString("x_post_id"),
					rs.getString("author_display_name"), rs.getString("author_username"),
					rs.getString("post_text"), createdAt, List.of());
		});
	}
}
