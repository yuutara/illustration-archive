package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class XLikeMediaRepository {
	private final JdbcTemplate jdbcTemplate;

	public XLikeMediaRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(long itemId, XLikeMedia media) {
		jdbcTemplate.update("""
				INSERT INTO x_like_media
				(x_like_item_id, media_key, sort_order, media_type, source_url, width, height)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", itemId, media.mediaKey(), media.sortOrder(), media.mediaType(),
				media.photoUrl(), media.width(), media.height());
	}

	public List<PendingMedia> findForPendingItems() {
		return jdbcTemplate.query("""
				SELECT m.x_like_item_id, m.media_key, m.sort_order, m.media_type,
				       m.source_url, m.width, m.height
				FROM x_like_media m
				JOIN x_like_item i ON i.id = m.x_like_item_id
				WHERE i.status = 'PENDING'
				ORDER BY m.x_like_item_id, m.sort_order
				""", (rs, row) -> new PendingMedia(rs.getLong("x_like_item_id"),
				new XLikeMedia(rs.getString("media_key"), rs.getInt("sort_order"),
						rs.getString("media_type"), rs.getString("source_url"),
						rs.getObject("width", Integer.class), rs.getObject("height", Integer.class))));
	}

	public record PendingMedia(long itemId, XLikeMedia media) {
	}
}
