package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Repository
public class XLikeRepository {
	public record ImportItem(long id, String xPostId, String xAuthorId,
			String authorUsername, String authorDisplayName, XLikeStatus status) {
	}
	// Name the key column: H2 also returns defaulted columns (likes_order) for RETURN_GENERATED_KEYS.
	private static final String[] GENERATED_ID = {"id"};
	private static final String INSERT = """
			INSERT INTO x_like_item (x_post_id, x_author_id, author_username,
			    author_display_name, post_text, post_created_at, status, discovered_at, updated_at, likes_order)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";
	private static final String FIND_PENDING_PAGE = """
			SELECT id, x_post_id, author_display_name, author_username, post_text, post_created_at, discovered_at
			FROM x_like_item
			WHERE status = 'PENDING'
			ORDER BY likes_order DESC, discovered_at DESC, id DESC
			LIMIT ? OFFSET ?
			""";

	private final JdbcTemplate jdbcTemplate;

	public XLikeRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Passed in rather than calling UTC_TIMESTAMP(3), which only MySQL has; these columns hold UTC on both. */
	private static LocalDateTime utcNow() {
		return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
	}

	/** The unique x_post_id key is the concurrency-safe source identity boundary. */
	public Long insertIfAbsent(XLikeCandidate candidate) {
		return insertIfAbsent(candidate, 0);
	}

	public Long insertIfAbsent(XLikeCandidate candidate, long likesOrder) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		try {
			jdbcTemplate.update(connection -> {
				var statement = connection.prepareStatement(INSERT, GENERATED_ID);
				statement.setString(1, candidate.xPostId());
				statement.setString(2, candidate.xAuthorId());
				statement.setString(3, candidate.authorUsername());
				statement.setString(4, candidate.authorDisplayName());
				statement.setString(5, candidate.postText());
				statement.setObject(6, candidate.postCreatedAt() == null ? null
						: LocalDateTime.ofInstant(candidate.postCreatedAt(), ZoneOffset.UTC));
				statement.setString(7, candidate.status().name());
				LocalDateTime now = utcNow();
				statement.setObject(8, now);
				statement.setObject(9, now);
				statement.setLong(10, likesOrder);
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

	public long highestLikesOrder() {
		return jdbcTemplate.queryForObject("SELECT GREATEST(COALESCE(MAX(likes_order), 0), 0) FROM x_like_item", Long.class);
	}

	public long lowestLikesOrder() {
		return jdbcTemplate.queryForObject("SELECT LEAST(COALESCE(MIN(likes_order), 0), 0) FROM x_like_item", Long.class);
	}

	/** Refresh observed order only; never change status, media, link or first discovery time. */
	public void updateLikesOrder(String postId, long likesOrder, boolean history) {
		if (history) {
			jdbcTemplate.update("UPDATE x_like_item SET likes_order = ? WHERE x_post_id = ? AND likes_order <= 0",
					likesOrder, postId);
		} else {
			// Within one scan keep the first occurrence; a later scan allocates strictly higher ranks.
			jdbcTemplate.update("UPDATE x_like_item SET likes_order = ? WHERE x_post_id = ? AND likes_order < ?",
					likesOrder, postId, likesOrder);
		}
	}

	/** Correct a retry's page ranks without moving earlier pages or a fresher head probe. */
	public void updateRecentOrder(String postId, long order, long floor, long first, long last) {
		jdbcTemplate.update("UPDATE x_like_item SET likes_order=? WHERE x_post_id=?"
				+ " AND (likes_order<=? OR likes_order BETWEEN ? AND ?)", order, postId, floor, last, first);
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
				SET status = 'SKIPPED', updated_at = ?
				WHERE id = ? AND status = 'PENDING'
				""", utcNow(), itemId);
	}

	/** Conditional update holds the row lock until the page transaction also refreshes media. */
	public Long reopenUnsupported(String postId) {
		int changed = jdbcTemplate.update("""
				UPDATE x_like_item SET status = 'PENDING', updated_at = ?
				WHERE x_post_id = ? AND status = 'UNSUPPORTED'
				""", utcNow(), postId);
		if (changed == 0) return null;
		return jdbcTemplate.queryForObject("SELECT id FROM x_like_item WHERE x_post_id = ?", Long.class, postId);
	}

	public java.util.Optional<ImportItem> findForImport(long itemId, boolean lock) {
		String sql = "SELECT id, x_post_id, x_author_id, author_username, author_display_name, status "
				+ "FROM x_like_item WHERE id = ?" + (lock ? " FOR UPDATE" : "");
		return jdbcTemplate.query(sql, (rs, row) -> new ImportItem(rs.getLong("id"),
				rs.getString("x_post_id"), rs.getString("x_author_id"),
				rs.getString("author_username"), rs.getString("author_display_name"),
				XLikeStatus.valueOf(rs.getString("status"))), itemId).stream().findFirst();
	}

	public void markImported(long itemId, long illustrationId) {
		int changed = jdbcTemplate.update("""
				UPDATE x_like_item SET status = 'IMPORTED', imported_illustration_id = ?,
				updated_at = ? WHERE id = ? AND status = 'PENDING'
				""", illustrationId, utcNow(), itemId);
		if (changed != 1) {
			throw new IllegalStateException("X Like item is no longer pending.");
		}
	}

	public long countPending() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM x_like_item WHERE status = 'PENDING'", Long.class);
	}

	public List<XLikeInboxItem> findPendingPage(int size, long offset) {
		return jdbcTemplate.query(FIND_PENDING_PAGE, (rs, row) -> {
			LocalDateTime created = rs.getObject("post_created_at", LocalDateTime.class);
			Instant createdAt = created == null ? null : created.toInstant(ZoneOffset.UTC);
			Instant discoveredAt = rs.getObject("discovered_at", LocalDateTime.class).toInstant(ZoneOffset.UTC);
			return new XLikeInboxItem(rs.getLong("id"), rs.getString("x_post_id"),
					rs.getString("author_display_name"), rs.getString("author_username"),
					rs.getString("post_text"), createdAt, discoveredAt, List.of());
		}, size, offset);
	}
}
