package com.yuutara.illustrationarchive.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;

@Repository
public class IllustrationRepository {

	private static final String INSERT_SQL = """
			INSERT INTO illustration (title, author_id, source_url, note)
			VALUES (NULL, NULL, NULL, NULL)
			""";

	private final JdbcTemplate jdbcTemplate;

	public IllustrationRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public long insert() {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS), keyHolder);

		Number generatedKey = keyHolder.getKey();
		if (generatedKey == null) {
			throw new IllegalStateException("Failed to obtain generated id for illustration insert.");
		}
		return generatedKey.longValue();
	}
}
