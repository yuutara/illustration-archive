package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.desktop.DesktopServer;
import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Skip, put back and the recently-skipped list against the real H2 schema the desktop app uses. */
class XLikeRepositoryH2Test {

	private JdbcTemplate jdbc;
	private XLikeRepository items;

	@BeforeEach
	void open() {
		var dataSource = new DriverManagerDataSource("jdbc:h2:mem:x-like-" + UUID.randomUUID()
				+ DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_DELAY=-1", "sa", "");
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2").load().migrate();
		jdbc = new JdbcTemplate(dataSource);
		items = new XLikeRepository(jdbc);
	}

	@AfterEach
	void close() {
		jdbc.execute("DROP ALL OBJECTS");
	}

	private long like(String postId, long order, XLikeStatus status) {
		return items.insertIfAbsent(new XLikeCandidate(postId, "a" + postId, "author" + postId, "Artist",
				null, null, status, List.of()), order);
	}

	private static List<Long> ids(List<XLikeInboxItem> page) {
		return page.stream().map(XLikeInboxItem::id).toList();
	}

	@Test
	void putBackReturnsASkippedLikeToItsOriginalPlaceInTheInbox() {
		long newest = like("3", 30, XLikeStatus.PENDING);
		long middle = like("2", 20, XLikeStatus.PENDING);
		long oldest = like("1", 10, XLikeStatus.PENDING);

		assertEquals(1, items.skipIfPending(middle));
		assertEquals(List.of(newest, oldest), ids(items.findPendingPage(10, 0)));
		assertEquals(1, items.countSkipped());

		assertEquals(1, items.unskipIfSkipped(middle));
		assertEquals(List.of(newest, middle, oldest), ids(items.findPendingPage(10, 0)), "back where it was, not on top");
		assertEquals(0, items.countSkipped());
		assertEquals(0, items.unskipIfSkipped(middle), "a second undo changes nothing");
	}

	@Test
	void putBackOnlyTouchesSkippedLikes() {
		long pending = like("1", 10, XLikeStatus.PENDING);
		long unsupported = like("2", 20, XLikeStatus.UNSUPPORTED);
		long imported = like("3", 30, XLikeStatus.PENDING);
		jdbc.update("UPDATE x_like_item SET status = 'IMPORTED' WHERE id = ?", imported);

		assertEquals(0, items.unskipIfSkipped(pending));
		assertEquals(0, items.unskipIfSkipped(unsupported));
		assertEquals(0, items.unskipIfSkipped(imported));
		assertEquals(0, items.unskipIfSkipped(999));
		assertEquals(List.of("PENDING", "UNSUPPORTED", "IMPORTED"), jdbc.queryForList(
				"SELECT status FROM x_like_item ORDER BY id", String.class));
	}

	@Test
	void anUndoOnlyTakesBackWhatItsOwnSkipChanged() {
		var service = new com.yuutara.illustrationarchive.service.XLikeSyncService(
				org.mockito.Mockito.mock(com.yuutara.illustrationarchive.service.XApiClient.class),
				org.mockito.Mockito.mock(com.yuutara.illustrationarchive.service.XLikePersistenceService.class), items,
				new XLikeMediaRepository(jdbc), org.mockito.Mockito.mock(XLikeSyncStateRepository.class), 5, 3,
				new com.yuutara.illustrationarchive.service.XLikeSyncBudget(25, 2, 6, 100, 50));
		long mine = like("1", 20, XLikeStatus.PENDING);
		long theirs = like("2", 10, XLikeStatus.PENDING);
		service.skip(List.of(theirs)); // another window, first

		var skip = service.skip(List.of(mine, theirs));
		assertEquals(List.of(mine), skip.skippedIds());
		// Even sent with both ids, the undo restores only this skip's item.
		assertEquals(1, service.unskip(List.of(mine, theirs), skip.skipToken()).restoredCount());
		assertEquals(List.of(mine), ids(items.findPendingPage(10, 0)));
		assertEquals(List.of(theirs), ids(items.findSkippedPage(10, 0)));

		// Skipped again: the old undo no longer applies, the new one does.
		var again = service.skip(List.of(mine));
		assertEquals(0, service.unskip(List.of(mine), skip.skipToken()).restoredCount());
		assertEquals(1, service.unskip(List.of(mine), again.skipToken()).restoredCount());
		service.skip(List.of(mine));
		assertEquals(0, items.countPending());

		// Recently skipped's put back takes whatever skipped item the user chose.
		assertEquals(2, service.unskip(List.of(mine, theirs)).restoredCount());
	}

	/** Two skips in one millisecond, made deterministic by giving both the same time: only the token tells them apart. */
	@Test
	void twoSkipsInTheSameMillisecondKeepTheirOwnUndo() {
		long first = like("1", 20, XLikeStatus.PENDING);
		long second = like("2", 10, XLikeStatus.PENDING);
		java.time.LocalDateTime sameMoment = java.time.LocalDateTime.of(2026, 11, 7, 12, 0, 0, 123_000_000);

		assertEquals(1, items.skipIfPending(first, sameMoment, "token-a"));
		assertEquals(1, items.skipIfPending(second, sameMoment, "token-b"));
		assertEquals(0, items.unskipIfSkippedBy(second, "token-a"), "another skip's token");

		// Put back and skipped again within the same millisecond: the old token is spent, the new one works.
		assertEquals(1, items.unskipIfSkipped(first));
		assertEquals(1, items.skipIfPending(first, sameMoment, "token-c"));
		assertEquals(0, items.unskipIfSkippedBy(first, "token-a"));
		assertEquals(List.of(second, first), ids(items.findSkippedPage(10, 0)), "the same skip time falls back to the newer row");
		assertEquals(1, items.unskipIfSkippedBy(first, "token-c"));
		assertEquals(0, items.unskipIfSkippedBy(first, "token-c"), "an undo only works once");
		assertEquals(1, items.unskipIfSkippedBy(second, "token-b"));
		assertEquals(java.util.Arrays.asList(null, null), jdbc.queryForList("SELECT skip_token FROM x_like_item ORDER BY id", String.class),
				"putting back clears the token");
	}

	@Test
	void recentlySkippedListsTheLatestSkipFirstAndPages() {
		long first = like("1", 30, XLikeStatus.PENDING);
		long second = like("2", 20, XLikeStatus.PENDING);
		long third = like("3", 10, XLikeStatus.PENDING);
		like("4", 40, XLikeStatus.PENDING);
		items.skipIfPending(first);
		items.skipIfPending(second);
		items.skipIfPending(third);
		// Skip time decides, not the like order or the id.
		jdbc.update("UPDATE x_like_item SET updated_at = TIMESTAMP '2026-11-07 10:00:00' WHERE id = ?", first);
		jdbc.update("UPDATE x_like_item SET updated_at = TIMESTAMP '2026-11-07 12:00:00' WHERE id = ?", second);
		jdbc.update("UPDATE x_like_item SET updated_at = TIMESTAMP '2026-11-07 11:00:00' WHERE id = ?", third);

		assertEquals(3, items.countSkipped());
		assertEquals(List.of(second, third, first), ids(items.findSkippedPage(10, 0)));
		assertEquals(List.of(third), ids(items.findSkippedPage(1, 1)));
	}
}
