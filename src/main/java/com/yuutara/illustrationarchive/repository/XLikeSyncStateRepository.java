package com.yuutara.illustrationarchive.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

@Repository
public class XLikeSyncStateRepository {
	public record State(String nextToken, int maxResults, String status) {
	}
	public record RecentState(String nextToken, int maxResults, String status, List<String> anchorIds,
			List<String> headIds, int matched, long orderNext, long orderFloor, boolean coverageToEnd) { }
	public record Account(String ownerId, String observedId, String observedUsername, LocalDateTime updatedAt) {
		/** Sent to the page and back: any change to the row gives another value, so a stale answer is recognised. */
		public String confirmation() { return ownerId + "/" + observedId + "/" + updatedAt; }
	}
	private static final ObjectMapper JSON = new ObjectMapper();

	private final JdbcTemplate jdbcTemplate;

	public XLikeSyncStateRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<State> load() {
		return jdbcTemplate.query("SELECT next_token, max_results, status FROM x_like_sync_state WHERE id = 1",
				(rs, row) -> new State(rs.getString("next_token"), rs.getInt("max_results"),
						rs.getString("status"))).stream().findFirst();
	}

	public boolean hasSeen(String token) {
		return !jdbcTemplate.query("SELECT token_sha256 FROM x_like_sync_seen_token WHERE sync_id = 1 AND token_sha256 = ?",
				(rs, row) -> rs.getBytes("token_sha256"), hash(token)).isEmpty();
	}

	@Transactional
	public void advance(String nextToken, int maxResults) {
		jdbcTemplate.update("INSERT INTO x_like_sync_seen_token (token_sha256) VALUES (?)", hash(nextToken));
		jdbcTemplate.update("""
				INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at)
				VALUES (1, ?, ?, 'ACTIVE', ?)
				ON DUPLICATE KEY UPDATE next_token = VALUES(next_token),
				    max_results = VALUES(max_results), status = 'ACTIVE', updated_at = VALUES(updated_at)
				""", nextToken, maxResults, utcNow());
	}

	public void markInvalid(int maxResults) {
		jdbcTemplate.update("""
				INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at)
				VALUES (1, NULL, ?, 'INVALID', ?)
				ON DUPLICATE KEY UPDATE next_token = NULL,
				    max_results = VALUES(max_results), status = 'INVALID', updated_at = VALUES(updated_at)
				""", maxResults, utcNow());
	}

	/** Remember a completed history scan so subsequent clicks do not pay to rescan it. */
	@Transactional
	public void markComplete(int maxResults) {
		jdbcTemplate.update("""
				INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at)
				VALUES (1, NULL, ?, 'COMPLETE', ?)
				ON DUPLICATE KEY UPDATE next_token = NULL, max_results = VALUES(max_results),
				    status = 'COMPLETE', updated_at = VALUES(updated_at)
				""", maxResults, utcNow());
		jdbcTemplate.update("DELETE FROM x_like_sync_seen_token WHERE sync_id = 1");
	}

	@Transactional
	public void clear() {
		jdbcTemplate.update("DELETE FROM x_like_sync_state WHERE id = 1");
		jdbcTemplate.update("DELETE FROM x_like_sync_seen_token WHERE sync_id = 1");
	}

	public Optional<RecentState> loadRecent() {
		return jdbcTemplate.query("""
				SELECT next_token,max_results,status,anchor_ids,head_ids,matched_count,
				       order_next,order_floor,coverage_to_end FROM x_like_sync_state WHERE id=2
				""", (rs, row) -> {
			try {
				return new RecentState(rs.getString("next_token"), rs.getInt("max_results"), rs.getString("status"),
						ids(rs.getString("anchor_ids")), ids(rs.getString("head_ids")), rs.getInt("matched_count"),
						rs.getLong("order_next"), rs.getLong("order_floor"), rs.getBoolean("coverage_to_end"));
			} catch (RuntimeException invalid) {
				return new RecentState(null, rs.getInt("max_results"), "INVALID", null, null, 0, 0, 0, false);
			}
		}).stream().findFirst();
	}

	public boolean recentHasSeen(String token) {
		return !jdbcTemplate.query("SELECT token_sha256 FROM x_like_sync_seen_token WHERE sync_id=2 AND token_sha256=?",
				(rs, row) -> rs.getBytes("token_sha256"), hash(token)).isEmpty();
	}

	/** Called inside the page transaction so data and its checkpoint commit together. */
	@Transactional
	public void saveRecent(RecentState state) {
		jdbcTemplate.update("""
				INSERT INTO x_like_sync_state (id,next_token,max_results,status,updated_at,anchor_ids,head_ids,
				    matched_count,order_next,order_floor,coverage_to_end)
				VALUES (2,?,?,?,?,?,?,?,?,?,?)
				ON DUPLICATE KEY UPDATE next_token=VALUES(next_token),max_results=VALUES(max_results),
				    status=VALUES(status),updated_at=VALUES(updated_at),anchor_ids=VALUES(anchor_ids),head_ids=VALUES(head_ids),
				    matched_count=VALUES(matched_count),order_next=VALUES(order_next),order_floor=VALUES(order_floor),
				    coverage_to_end=VALUES(coverage_to_end)
				""", state.nextToken(), state.maxResults(), state.status(), utcNow(), json(state.anchorIds()), json(state.headIds()),
				state.matched(), state.orderNext(), state.orderFloor(), state.coverageToEnd());
		if (state.nextToken() != null && "ACTIVE".equals(state.status())) {
			jdbcTemplate.update("INSERT IGNORE INTO x_like_sync_seen_token (sync_id,token_sha256) VALUES (2,?)", hash(state.nextToken()));
		}
	}

	@Transactional
	public void restartRecent(RecentState state) {
		jdbcTemplate.update("DELETE FROM x_like_sync_seen_token WHERE sync_id=2");
		saveRecent(state);
	}

	public Optional<Account> loadAccount() {
		return jdbcTemplate.query("""
				SELECT owner_x_user_id, observed_x_user_id, observed_x_username, updated_at FROM x_like_sync_account WHERE id = 1
				""", (rs, row) -> new Account(rs.getString("owner_x_user_id"), rs.getString("observed_x_user_id"),
						rs.getString("observed_x_username"), rs.getObject("updated_at", LocalDateTime.class)))
				.stream().findFirst();
	}

	public boolean hasProgress() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM x_like_sync_state", Long.class) > 0;
	}

	public void bindAccount(String ownerId) {
		jdbcTemplate.update("""
				INSERT INTO x_like_sync_account (id, owner_x_user_id, observed_x_user_id, observed_x_username, updated_at)
				VALUES (1, ?, NULL, NULL, ?)
				ON DUPLICATE KEY UPDATE owner_x_user_id = VALUES(owner_x_user_id), observed_x_user_id = NULL,
				    observed_x_username = NULL, updated_at = VALUES(updated_at)
				""", ownerId, utcNow());
	}

	/** Remembers who was refused, so the Inbox can say so without paying to ask X again. The owner stays. */
	public void observeAccount(String userId, String username) {
		jdbcTemplate.update("""
				INSERT INTO x_like_sync_account (id, owner_x_user_id, observed_x_user_id, observed_x_username, updated_at)
				VALUES (1, NULL, ?, ?, ?)
				ON DUPLICATE KEY UPDATE observed_x_user_id = VALUES(observed_x_user_id),
				    observed_x_username = VALUES(observed_x_username), updated_at = VALUES(updated_at)
				""", userId, username, utcNow());
	}

	/** New credentials make the last sighting stale; the next sync looks again. */
	public void forgetObservedAccount() {
		jdbcTemplate.update("""
				UPDATE x_like_sync_account SET observed_x_user_id = NULL, observed_x_username = NULL, updated_at = ?
				WHERE id = 1
				""", utcNow());
	}

	/**
	 * Gives the progress to the account the user was shown, compared in the UPDATE itself: reconnecting clears the
	 * sighting from outside the sync lock, so a check made before this statement could already be out of date.
	 *
	 * @return false when the row has changed since {@code seen} was read; nothing was written
	 */
	public boolean settleAccount(Account seen) {
		return jdbcTemplate.update("""
				UPDATE x_like_sync_account
				SET owner_x_user_id = ?, observed_x_user_id = NULL, observed_x_username = NULL, updated_at = ?
				WHERE id = 1 AND COALESCE(owner_x_user_id, '') = ? AND observed_x_user_id = ? AND updated_at = ?
				""", seen.observedId(), utcNow(), seen.ownerId() == null ? "" : seen.ownerId(), seen.observedId(),
				seen.updatedAt()) == 1;
	}

	/** All or nothing: either the old progress is gone and that account owns the clean slate, or nothing changed. */
	@Transactional
	public boolean restartFor(Account seen) {
		if (!settleAccount(seen)) return false;
		jdbcTemplate.update("DELETE FROM x_like_sync_state");
		jdbcTemplate.update("DELETE FROM x_like_sync_seen_token");
		return true;
	}

	/** Passed in rather than calling UTC_TIMESTAMP(3), which only MySQL has; updated_at holds UTC on both. */
	private static LocalDateTime utcNow() {
		return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
	}

	private static List<String> ids(String json) { return json == null ? null : List.of(JSON.readValue(json, String[].class)); }
	private static String json(List<String> ids) { return ids == null ? null : JSON.writeValueAsString(ids); }

	private byte[] hash(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}
}
