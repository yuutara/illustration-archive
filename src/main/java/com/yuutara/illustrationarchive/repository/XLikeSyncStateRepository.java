package com.yuutara.illustrationarchive.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Repository
public class XLikeSyncStateRepository {
	public record State(String nextToken, int maxResults, String status) {
	}

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
		return !jdbcTemplate.query("SELECT token_sha256 FROM x_like_sync_seen_token WHERE token_sha256 = ?",
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

	@Transactional
	public void clear() {
		jdbcTemplate.update("DELETE FROM x_like_sync_state WHERE id = 1");
		jdbcTemplate.update("DELETE FROM x_like_sync_seen_token");
	}

	private byte[] hash(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}
}
