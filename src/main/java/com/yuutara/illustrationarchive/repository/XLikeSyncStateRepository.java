package com.yuutara.illustrationarchive.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

@Repository
public class XLikeSyncStateRepository {
	public record State(String nextToken, int maxResults, String status) {
	}
	public record RecentState(String nextToken, int maxResults, String status, List<String> anchorIds,
			List<String> headIds, int matched, long orderNext, long orderFloor, boolean coverageToEnd) { }
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
				VALUES (1, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3))
				ON DUPLICATE KEY UPDATE next_token = VALUES(next_token),
				    max_results = VALUES(max_results), status = 'ACTIVE', updated_at = UTC_TIMESTAMP(3)
				""", nextToken, maxResults);
	}

	public void markInvalid(int maxResults) {
		jdbcTemplate.update("""
				INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at)
				VALUES (1, NULL, ?, 'INVALID', UTC_TIMESTAMP(3))
				ON DUPLICATE KEY UPDATE next_token = NULL,
				    max_results = VALUES(max_results), status = 'INVALID', updated_at = UTC_TIMESTAMP(3)
				""", maxResults);
	}

	/** Remember a completed history scan so subsequent clicks do not pay to rescan it. */
	@Transactional
	public void markComplete(int maxResults) {
		jdbcTemplate.update("""
				INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at)
				VALUES (1, NULL, ?, 'COMPLETE', UTC_TIMESTAMP(3))
				ON DUPLICATE KEY UPDATE next_token = NULL, max_results = VALUES(max_results),
				    status = 'COMPLETE', updated_at = UTC_TIMESTAMP(3)
				""", maxResults);
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
				VALUES (2,?,?,?,UTC_TIMESTAMP(3),?,?,?,?,?,?)
				ON DUPLICATE KEY UPDATE next_token=VALUES(next_token),max_results=VALUES(max_results),
				    status=VALUES(status),updated_at=VALUES(updated_at),anchor_ids=VALUES(anchor_ids),head_ids=VALUES(head_ids),
				    matched_count=VALUES(matched_count),order_next=VALUES(order_next),order_floor=VALUES(order_floor),
				    coverage_to_end=VALUES(coverage_to_end)
				""", state.nextToken(), state.maxResults(), state.status(), json(state.anchorIds()), json(state.headIds()),
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
