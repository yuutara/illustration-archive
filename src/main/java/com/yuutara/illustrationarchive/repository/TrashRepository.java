package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.dto.TrashItem;
import com.yuutara.illustrationarchive.repository.PendingAssetCleanupRepository.AssetFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Persistence helpers for desktop Trash: moving gallery works to trash, restoring them, listing
 * trashed items, and locking/deleting trashed works for permanent purge.
 */
@Repository
public class TrashRepository {

	private final JdbcTemplate jdbcTemplate;

	public TrashRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<Long> lockActiveIds(List<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbcTemplate.queryForList(
				"SELECT id FROM illustration WHERE trashed_at IS NULL AND id IN (" + marks(ids.size()) + ") ORDER BY id FOR UPDATE",
				Long.class,
				ids.toArray()
		);
	}

	public int markTrashed(List<Long> ids, Instant trashedAt) {
		if (ids.isEmpty()) {
			return 0;
		}
		LocalDateTime utc = toUtc(trashedAt);
		Object[] params = new Object[ids.size() + 1];
		params[0] = utc;
		for (int i = 0; i < ids.size(); i++) {
			params[i + 1] = ids.get(i);
		}
		return jdbcTemplate.update(
				"UPDATE illustration SET trashed_at = ? WHERE trashed_at IS NULL AND id IN (" + marks(ids.size()) + ")",
				params
		);
	}

	public List<Long> lockTrashedIds(List<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbcTemplate.queryForList(
				"SELECT id FROM illustration WHERE trashed_at IS NOT NULL AND id IN (" + marks(ids.size()) + ") ORDER BY id FOR UPDATE",
				Long.class,
				ids.toArray()
		);
	}

	public int clearTrashed(List<Long> ids) {
		if (ids.isEmpty()) {
			return 0;
		}
		return jdbcTemplate.update(
				"UPDATE illustration SET trashed_at = NULL WHERE trashed_at IS NOT NULL AND id IN (" + marks(ids.size()) + ")",
				ids.toArray()
		);
	}

	public List<Long> lockAllTrashedIds(int limit) {
		return jdbcTemplate.queryForList(
				"SELECT id FROM illustration WHERE trashed_at IS NOT NULL ORDER BY trashed_at ASC, id ASC LIMIT ? FOR UPDATE",
				Long.class,
				limit
		);
	}

	public List<Long> lockExpiredTrashedIds(Instant cutoff, int limit) {
		return jdbcTemplate.queryForList(
				"SELECT id FROM illustration WHERE trashed_at IS NOT NULL AND trashed_at <= ? ORDER BY trashed_at ASC, id ASC LIMIT ? FOR UPDATE",
				Long.class,
				toUtc(cutoff),
				limit
		);
	}

	public List<AssetFile> findAssetFilesByIllustrationIds(List<Long> illustrationIds) {
		if (illustrationIds.isEmpty()) {
			return List.of();
		}
		return jdbcTemplate.query(
				"SELECT storage_key, mime_type FROM asset WHERE illustration_id IN (" + marks(illustrationIds.size()) + ") ORDER BY id ASC",
				(rs, rowNum) -> new AssetFile(rs.getString("storage_key"), rs.getString("mime_type")),
				illustrationIds.toArray()
		);
	}

	public int deleteTrashedByIds(List<Long> ids) {
		if (ids.isEmpty()) {
			return 0;
		}
		return jdbcTemplate.update(
				"DELETE FROM illustration WHERE trashed_at IS NOT NULL AND id IN (" + marks(ids.size()) + ")",
				ids.toArray()
		);
	}

	public long countTrashed() {
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM illustration WHERE trashed_at IS NOT NULL", Long.class);
		return count == null ? 0L : count;
	}

	public List<TrashItem> findTrashedPage(int size, long offset, int retentionDays) {
		Duration retention = Duration.ofDays(retentionDays);
		return jdbcTemplate.query("""
				SELECT
					i.id AS illustration_id,
					i.title AS illustration_title,
					i.source_url AS illustration_source_url,
					i.trashed_at AS illustration_trashed_at,
					a.id AS author_id,
					a.display_name AS author_display_name,
					a.x_username AS author_x_username,
					cover_asset.id AS cover_asset_id,
					cover_asset.mime_type AS cover_mime_type,
					(SELECT COUNT(*) FROM asset asset_count WHERE asset_count.illustration_id = i.id) AS asset_count
				FROM illustration i
				LEFT JOIN author a ON i.author_id = a.id
				LEFT JOIN asset cover_asset ON cover_asset.id = (
					SELECT candidate.id
					FROM asset candidate
					WHERE candidate.illustration_id = i.id
					ORDER BY candidate.sort_order ASC, candidate.id ASC
					LIMIT 1
				)
				WHERE i.trashed_at IS NOT NULL
				ORDER BY i.trashed_at DESC, i.id DESC
				LIMIT ? OFFSET ?
				""", (rs, rowNum) -> {
			Long authorId = rs.getObject("author_id", Long.class);
			AuthorSummary author = authorId == null ? null : new AuthorSummary(
					authorId,
					rs.getString("author_display_name"),
					rs.getString("author_x_username")
			);
			Instant trashedAt = rs.getObject("illustration_trashed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC);
			return new TrashItem(
					rs.getLong("illustration_id"),
					rs.getString("illustration_title"),
					author,
					rs.getString("illustration_source_url"),
					rs.getObject("cover_asset_id", Long.class),
					rs.getString("cover_mime_type"),
					rs.getInt("asset_count"),
					trashedAt,
					trashedAt.plus(retention)
			);
		}, size, offset);
	}

	private static LocalDateTime toUtc(Instant instant) {
		return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
	}

	private static String marks(int count) {
		return String.join(", ", java.util.Collections.nCopies(count, "?"));
	}
}
