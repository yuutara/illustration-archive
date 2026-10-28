package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.dto.GalleryAsset;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryItem;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryQuery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour contract of the gallery list read (count, page, page assets) against a real database.
 * The H2 and MySQL subclasses run the same seed and assertions, so a dialect difference fails here
 * instead of in browser acceptance. Expected ids are written out by hand, never recomputed from SQL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IllustrationGalleryReadContract {

	private static final LocalDateTime T1 = LocalDateTime.of(2026, 1, 1, 10, 0);
	private static final LocalDateTime T2 = LocalDateTime.of(2026, 1, 2, 10, 0);
	private static final LocalDateTime T3 = LocalDateTime.of(2026, 1, 3, 10, 0);
	private static final AuthorSummary SAME_A = new AuthorSummary(1L, "Same artist", "same_a");
	private static final AuthorSummary SAME_B = new AuthorSummary(2L, "Same artist", "Same_B");
	private static final AuthorSummary MORI = new AuthorSummary(3L, "Mori 森", null);
	// Newest first; id 2 is imported later than 3-7 to prove created_at outranks id.
	private static final List<Long> ALL = List.of(9L, 8L, 2L, 7L, 6L, 5L, 4L, 3L, 1L);

	private IllustrationRepository repository;

	/** A migrated, empty database reserved for this test class. */
	protected abstract DataSource openDatabase() throws Exception;

	/** Releases the database created by {@link #openDatabase()}. */
	protected abstract void closeDatabase() throws Exception;

	static void migrate(DataSource dataSource, String location) {
		Flyway.configure().dataSource(dataSource).locations(location).load().migrate();
	}

	@BeforeAll
	void seed() throws Exception {
		DataSource dataSource = openDatabase();
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.update("INSERT INTO author (id, display_name, x_username) VALUES (1, 'Same artist', 'same_a'),"
				+ " (2, 'Same artist', 'Same_B'), (3, 'Mori 森', NULL)");
		jdbc.update("INSERT INTO tag (id, name) VALUES (1, '探索风景'), (2, '探索旅行'), (3, '100%_!'), (4, 'unused')");
		work(jdbc, 1, "夏日 1", 1L, "needle 备注", T1);
		work(jdbc, 2, "夏日 2", 1L, null, T3);
		work(jdbc, 3, "100%_! report", 2L, null, T1);
		work(jdbc, 4, "100 percent", 2L, null, T1);
		work(jdbc, 5, "a_b", null, null, T2);
		work(jdbc, 6, "axb", null, null, T2);
		work(jdbc, 7, "Bang!", 3L, null, T2);
		work(jdbc, 8, null, 3L, null, T3);
		work(jdbc, 9, "Mixed", null, null, T3);
		jdbc.update("INSERT INTO illustration_tag (illustration_id, tag_id) VALUES (1, 1), (2, 1), (9, 1), (9, 2), (3, 2), (5, 3)");
		// Ids deliberately disagree with sort_order; work 2 has a sort_order tie broken by id; work 8 has no asset.
		asset(jdbc, 11, 1, "image/png", 2);
		asset(jdbc, 12, 1, "image/gif", 0);
		asset(jdbc, 13, 1, "image/jpeg", 1);
		asset(jdbc, 22, 2, "image/png", 0);
		asset(jdbc, 21, 2, "image/jpeg", 0);
		asset(jdbc, 31, 3, "image/png", 0);
		asset(jdbc, 41, 4, "image/png", 0);
		asset(jdbc, 51, 5, "image/png", 0);
		asset(jdbc, 61, 6, "image/png", 0);
		asset(jdbc, 71, 7, "video/mp4", 0);
		asset(jdbc, 92, 9, "image/png", 1);
		asset(jdbc, 91, 9, "image/png", 0);
		repository = GalleryMapperSupport.repository(jdbc);
	}

	@AfterAll
	void release() throws Exception {
		closeDatabase();
	}

	private static void work(JdbcTemplate jdbc, long id, String title, Long authorId, String note, LocalDateTime createdAt) {
		jdbc.update("INSERT INTO illustration (id, title, author_id, note, created_at) VALUES (?, ?, ?, ?, ?)",
				id, title, authorId, note, Timestamp.valueOf(createdAt));
	}

	private static void asset(JdbcTemplate jdbc, long id, long illustrationId, String mimeType, int sortOrder) {
		jdbc.update("INSERT INTO asset (id, illustration_id, original_filename, storage_key, mime_type, file_size, sort_order)"
				+ " VALUES (?, ?, ?, ?, ?, 1, ?)", id, illustrationId, "f" + id, "contract/" + id, mimeType, sortOrder);
	}

	private static IllustrationGalleryQuery q(String q) {
		return new IllustrationGalleryQuery(q, null, null);
	}

	private static IllustrationGalleryQuery ids(String q, List<Long> authorIds, List<Long> tagIds) {
		return IllustrationGalleryQuery.withIds(q, authorIds, tagIds);
	}

	/** Count and the whole result (in one page) must agree with the expected ids. */
	private void assertResult(IllustrationGalleryQuery query, List<Long> expected) {
		assertEquals(expected.size(), repository.count(query), "count");
		assertEquals(expected, repository.findGalleryPage(query, 100, 0).stream().map(IllustrationGalleryItem::id).toList());
	}

	@Test
	void unfilteredListIsNewestFirstWithIdBreakingTimeTies() {
		assertResult(q(null), ALL);
	}

	@Test
	void mapsAuthorCoverAssetCountAndCreatedAt() {
		Map<Long, IllustrationGalleryItem> items = new java.util.HashMap<>();
		repository.findGalleryPage(q(null), 100, 0).forEach(item -> items.put(item.id(), item));

		assertEquals(new IllustrationGalleryItem(1L, "夏日 1", SAME_A, 12L, "image/gif", 3, T1, List.of()), items.get(1L));
		assertEquals(new IllustrationGalleryItem(2L, "夏日 2", SAME_A, 21L, "image/jpeg", 2, T3, List.of()), items.get(2L));
		assertEquals(new IllustrationGalleryItem(3L, "100%_! report", SAME_B, 31L, "image/png", 1, T1, List.of()), items.get(3L));
		assertEquals(new IllustrationGalleryItem(6L, "axb", null, 61L, "image/png", 1, T2, List.of()), items.get(6L));
		assertEquals(new IllustrationGalleryItem(7L, "Bang!", MORI, 71L, "video/mp4", 1, T2, List.of()), items.get(7L));
		assertEquals(new IllustrationGalleryItem(8L, null, MORI, null, null, 0, T3, List.of()), items.get(8L));
	}

	@Test
	void keywordMatchesTitleNoteAuthorHandleAndTagCaseInsensitively() {
		assertResult(q("needle"), List.of(1L));
		assertResult(q("夏日"), List.of(2L, 1L));
		assertResult(q("same ARTIST"), List.of(2L, 4L, 3L, 1L));
		assertResult(q("same_b"), List.of(4L, 3L));
		assertResult(q("@SAME_B"), List.of(4L, 3L));
		assertResult(q("@same_a"), List.of(2L, 1L));
		assertResult(q("风景"), List.of(9L, 2L, 1L));
		assertResult(q("不存在的关键词"), List.of());
	}

	@Test
	void keywordWildcardsAndEscapeCharacterAreLiteral() {
		assertResult(q("100%"), List.of(5L, 3L));
		assertResult(q("a_b"), List.of(5L));
		assertResult(q("_"), List.of(2L, 5L, 4L, 3L, 1L));
		assertResult(q("g!"), List.of(7L));
		assertResult(q("%_!"), List.of(5L, 3L));
	}

	@Test
	void authorsAndTagsAreOrWithinAGroupAndAndAcrossGroups() {
		assertResult(ids(null, List.of(1L), List.of()), List.of(2L, 1L));
		assertResult(ids(null, List.of(2L, 1L), List.of()), List.of(2L, 4L, 3L, 1L));
		assertResult(ids(null, List.of(), List.of(1L)), List.of(9L, 2L, 1L));
		assertResult(ids(null, List.of(), List.of(2L, 1L)), List.of(9L, 2L, 3L, 1L));
		assertResult(ids(null, List.of(1L, 2L), List.of(1L, 2L)), List.of(2L, 3L, 1L));
		assertResult(ids("夏日", List.of(1L, 2L), List.of(1L, 2L)), List.of(2L, 1L));
		assertResult(ids(null, List.of(1L, 999L), List.of()), List.of(2L, 1L));
		assertResult(ids(null, List.of(999L), List.of()), List.of());
		assertResult(ids(null, List.of(), List.of(4L)), List.of());
	}

	@Test
	void pagesNeitherSkipNorRepeatAndStopAtTheEnd() {
		assertPaged(q(null), 2, ALL);
		assertPaged(ids(null, List.of(), List.of(1L, 2L)), 3, List.of(9L, 2L, 3L, 1L));
		assertTrue(repository.findGalleryPage(q(null), 5, 9).isEmpty());
		assertTrue(repository.findGalleryPage(q(null), 5, 100).isEmpty());
	}

	private void assertPaged(IllustrationGalleryQuery query, int size, List<Long> expected) {
		List<Long> seen = new ArrayList<>();
		for (long offset = 0; offset < expected.size(); offset += size) {
			List<IllustrationGalleryItem> page = repository.findGalleryPage(query, size, offset);
			assertTrue(page.size() <= size);
			page.forEach(item -> seen.add(item.id()));
		}
		assertEquals(expected, seen);
		assertEquals(seen.size(), new HashSet<>(seen).size());
		assertEquals(expected.size(), repository.count(query));
	}

	@Test
	void pageAssetsAreGroupedByWorkInSortOrderAndSkipWorksWithoutAssets() {
		Map<Long, List<GalleryAsset>> assets = repository.findGalleryAssetsByIllustrationIds(List.of(9L, 1L, 8L, 2L));

		assertEquals(List.of(1L, 2L, 9L), new ArrayList<>(assets.keySet()));
		assertEquals(List.of(new GalleryAsset(12L, "image/gif", 0), new GalleryAsset(13L, "image/jpeg", 1),
				new GalleryAsset(11L, "image/png", 2)), assets.get(1L));
		assertEquals(List.of(new GalleryAsset(21L, "image/jpeg", 0), new GalleryAsset(22L, "image/png", 0)), assets.get(2L));
		assertEquals(List.of(new GalleryAsset(91L, "image/png", 0), new GalleryAsset(92L, "image/png", 1)), assets.get(9L));
		assertEquals(Map.of(), repository.findGalleryAssetsByIllustrationIds(List.of()));
		assertEquals(Map.of(), repository.findGalleryAssetsByIllustrationIds(List.of(8L, 999L)));
	}
}
