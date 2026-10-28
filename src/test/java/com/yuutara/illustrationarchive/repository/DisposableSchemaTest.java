package com.yuutara.illustrationarchive.repository;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DisposableSchemaTest {

	private static final String DROP = "DROP DATABASE IF EXISTS `illustration_archive_gallery_test_1`";

	private final JdbcTemplate server = mock(JdbcTemplate.class);
	private final DisposableSchema schema = new DisposableSchema(server, "illustration_archive_gallery_test_1");

	@Test
	void failedCreateNeverRunsSetupOrDropsTheSchemaItDidNotCreate() {
		var exists = new BadSqlGrammarException("create", "CREATE DATABASE", new SQLException("database exists"));
		doThrow(exists).when(server).execute(startsWith("CREATE DATABASE"));
		var setupRan = new AtomicBoolean();

		assertSame(exists, assertThrows(BadSqlGrammarException.class, () -> schema.createThen(() -> setupRan.getAndSet(true))));
		schema.dropIfCreated();

		assertFalse(setupRan.get());
		verify(server, never()).execute(startsWith("DROP"));
	}

	@Test
	void migrationFailureAfterCreateDropsTheNewSchemaAndRethrows() {
		var migration = new IllegalStateException("migration failed");

		assertSame(migration, assertThrows(IllegalStateException.class, () -> schema.createThen(() -> {
			throw migration;
		})));
		schema.dropIfCreated();

		InOrder order = inOrder(server);
		order.verify(server).execute(startsWith("CREATE DATABASE `illustration_archive_gallery_test_1`"));
		order.verify(server).execute(DROP);
		verify(server, times(2)).execute(anyString());
	}

	@Test
	void seedFailureAfterSetupIsCleanedUpOnceByTeardown() {
		assertEquals("ready", schema.createThen(() -> "ready"));
		verify(server, never()).execute(startsWith("DROP"));

		// The contract's @AfterAll calls this whether or not seeding succeeded.
		schema.dropIfCreated();
		schema.dropIfCreated();

		verify(server, times(1)).execute(DROP);
	}

	@Test
	void cleanupFailureDoesNotHideTheSetupFailure() {
		var cleanup = new DataAccessResourceFailureException("server gone");
		doThrow(cleanup).when(server).execute(DROP);
		var migration = new IllegalStateException("migration failed");

		var thrown = assertThrows(IllegalStateException.class, () -> schema.createThen(() -> {
			throw migration;
		}));

		assertSame(migration, thrown);
		assertSame(cleanup, thrown.getSuppressed()[0]);
	}
}
