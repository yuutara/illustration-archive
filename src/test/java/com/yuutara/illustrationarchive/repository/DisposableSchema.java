package com.yuutara.illustrationarchive.repository;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.function.Supplier;

/**
 * A MySQL schema that a test creates for itself and may therefore drop. Ownership starts only after
 * {@code CREATE DATABASE} succeeds: if creation fails (for example the name already exists), nothing
 * is ever dropped, so a test can never delete a schema it did not make.
 */
final class DisposableSchema {

	private final JdbcTemplate server;
	private final String name;
	private boolean created;

	DisposableSchema(JdbcTemplate server, String name) {
		this.server = server;
		this.name = name;
	}

	/**
	 * Creates the schema, then runs {@code setup} (migration, connection). If setup fails, the schema
	 * just created is dropped before the failure propagates. Later failures (seeding) are cleaned up by
	 * {@link #dropIfCreated()} from the test's teardown.
	 */
	<T> T createThen(Supplier<T> setup) {
		server.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
		created = true;
		try {
			return setup.get();
		} catch (RuntimeException | Error failure) {
			try {
				dropIfCreated();
			} catch (RuntimeException cleanup) {
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
	}

	/** Drops the schema only if this instance created it; safe to call repeatedly. */
	void dropIfCreated() {
		if (!created) {
			return;
		}
		server.execute("DROP DATABASE IF EXISTS `" + name + "`");
		created = false;
	}
}
