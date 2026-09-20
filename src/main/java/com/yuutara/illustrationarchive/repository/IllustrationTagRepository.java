package com.yuutara.illustrationarchive.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IllustrationTagRepository {

	private static final String DELETE_BY_ILLUSTRATION_ID_SQL =
			"DELETE FROM illustration_tag WHERE illustration_id = ?";

	private static final String INSERT_SQL = """
			INSERT INTO illustration_tag (illustration_id, tag_id)
			VALUES (?, ?)
			""";

	private final JdbcTemplate jdbcTemplate;

	public IllustrationTagRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void deleteByIllustrationId(long illustrationId) {
		jdbcTemplate.update(DELETE_BY_ILLUSTRATION_ID_SQL, illustrationId);
	}

	public void insert(long illustrationId, long tagId) {
		jdbcTemplate.update(INSERT_SQL, illustrationId, tagId);
	}
}
