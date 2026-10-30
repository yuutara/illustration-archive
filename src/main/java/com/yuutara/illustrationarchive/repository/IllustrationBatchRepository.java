package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.IllustrationBatchTagCount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes that touch several works at once. Every statement is safe to repeat, so a request whose reply was
 * lost can simply be sent again: adding skips links that already exist, removing skips links that are gone.
 * All lists must be non-empty; {@code IllustrationBatchUpdateService} validates them first.
 */
@Repository
public class IllustrationBatchRepository {

	private final JdbcTemplate jdbcTemplate;

	public IllustrationBatchRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<Long> findExistingIds(List<Long> ids) {
		return jdbcTemplate.queryForList("SELECT id FROM illustration WHERE id IN (" + marks(ids) + ")",
				Long.class, ids.toArray());
	}

	public void updateAuthor(List<Long> ids, Long authorId) {
		List<Object> parameters = new ArrayList<>();
		parameters.add(authorId);
		parameters.addAll(ids);
		jdbcTemplate.update("UPDATE illustration SET author_id = ? WHERE id IN (" + marks(ids) + ")", parameters.toArray());
	}

	public void removeTags(List<Long> ids, List<Long> tagIds) {
		jdbcTemplate.update("DELETE FROM illustration_tag WHERE illustration_id IN (" + marks(ids)
				+ ") AND tag_id IN (" + marks(tagIds) + ")", concat(ids, tagIds));
	}

	public void addTags(List<Long> ids, List<Long> tagIds) {
		jdbcTemplate.update("""
				INSERT INTO illustration_tag (illustration_id, tag_id)
				SELECT i.id, t.id
				FROM illustration i
				JOIN tag t ON t.id IN (%s)
				WHERE i.id IN (%s)
					AND NOT EXISTS (SELECT 1 FROM illustration_tag existing
						WHERE existing.illustration_id = i.id AND existing.tag_id = t.id)
				""".formatted(marks(tagIds), marks(ids)), concat(tagIds, ids));
	}

	public List<IllustrationBatchTagCount> countTags(List<Long> ids) {
		return jdbcTemplate.query("""
				SELECT t.id, t.name, COUNT(*) AS works
				FROM illustration_tag it
				JOIN tag t ON t.id = it.tag_id
				WHERE it.illustration_id IN (%s)
				GROUP BY t.id, t.name
				ORDER BY works DESC, t.name ASC, t.id ASC
				""".formatted(marks(ids)), (row, number) -> new IllustrationBatchTagCount(
				row.getLong("id"), row.getString("name"), row.getLong("works")), ids.toArray());
	}

	private static String marks(List<Long> values) {
		return String.join(", ", Collections.nCopies(values.size(), "?"));
	}

	private static Object[] concat(List<Long> first, List<Long> second) {
		List<Object> parameters = new ArrayList<>(first);
		parameters.addAll(second);
		return parameters.toArray();
	}
}
