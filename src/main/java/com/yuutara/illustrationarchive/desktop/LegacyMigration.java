package com.yuutara.illustrationarchive.desktop;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Copies a MySQL archive into a new desktop library. It is an explicit, one-off operation and is built so that
 * neither side can be damaged:
 *
 * <ul>
 * <li>The source is only read: a read-only transaction on MySQL, one snapshot for every table, and media files are
 * opened for reading only.</li>
 * <li>The target must not exist yet. The copy is built in a sibling staging folder and renamed into place only after
 * every row count and file hash checks out, so an existing library is never written to and a failed run leaves
 * nothing behind.</li>
 * <li>Without {@code --execute} the same copy runs into an in-memory H2 and writes no files at all, which proves the
 * data fits before anything is created.</li>
 * </ul>
 *
 * <p>IDs are preserved, so Inbox items keep pointing at their archived works and the sync checkpoints stay valid.
 * X tokens are not carried over: they belong to the old confidential client, and the desktop app signs in with its
 * own PKCE login.
 */
public final class LegacyMigration {

	public static final String COMMAND = "--migrate-from-mysql";
	/** Parents before children, so foreign keys hold throughout. */
	private static final List<String> TABLES = List.of("author", "tag", "illustration", "asset", "illustration_tag",
			"x_like_item", "x_like_media", "x_like_sync_state", "x_like_sync_seen_token", "x_like_sync_account");
	private static final List<String> GENERATED_ID_TABLES = List.of("author", "tag", "illustration", "asset",
			"x_like_item", "x_like_media");

	public record Result(Map<String, Long> rows, long files, long bytes, List<String> problems) {
	}

	private record AssetFile(String storageKey, String sha256) {
	}

	private LegacyMigration() {
	}

	/** Runs the whole copy against an in-memory H2 and only reads media files. */
	static Result check(DataSource source, Path mediaRoot) throws SQLException, IOException {
		String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_DELAY=-1";
		DataSource memory = new DriverManagerDataSource(url, "sa", "");
		try {
			return copy(source, mediaRoot, memory, null);
		} finally {
			try (Connection connection = memory.getConnection(); Statement statement = connection.createStatement()) {
				statement.execute("DROP ALL OBJECTS");
			}
		}
	}

	/**
	 * @param allowProblems accept missing or changed media files, which then stay missing in the new library
	 * @throws IllegalStateException when the target is taken or the copy found problems; nothing is left behind
	 */
	static Result execute(DataSource source, Path mediaRoot, Path target, boolean allowProblems) throws SQLException, IOException {
		Path destination = target.toAbsolutePath().normalize();
		if (Files.exists(destination) && !isEmptyDirectory(destination)) {
			throw new IllegalStateException("Target already exists and is not empty, so nothing was changed: " + destination);
		}
		Path staging = destination.resolveSibling(destination.getFileName() + ".migrating-"
				+ DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()));
		Files.createDirectories(staging);
		boolean moved = false;
		try {
			String url = "jdbc:h2:file:" + staging.resolve("data").resolve("archive") + DesktopServer.H2_COMPATIBILITY
					+ ";DB_CLOSE_ON_EXIT=FALSE";
			Result result = copy(source, mediaRoot, new DriverManagerDataSource(url, "sa", ""), staging.resolve("media"));
			if (!result.problems().isEmpty() && !allowProblems) {
				throw new IllegalStateException(result.problems().size() + " media problem(s) found, so nothing was changed. First: "
						+ result.problems().get(0));
			}
			if (Files.exists(destination)) {
				Files.delete(destination);
			}
			Files.move(staging, destination);
			moved = true;
			return result;
		} finally {
			if (!moved) {
				deleteTree(staging);
			}
		}
	}

	private static Result copy(DataSource source, Path mediaRoot, DataSource target, Path mediaTarget)
			throws SQLException, IOException {
		Flyway.configure().dataSource(target).locations("classpath:db/migration-h2").load().migrate();
		Map<String, Long> rows = new LinkedHashMap<>();
		List<AssetFile> assets = new ArrayList<>();
		try (Connection from = source.getConnection(); Connection to = target.getConnection()) {
			// Read-only first: MySQL refuses to change this inside a transaction. One transaction is one snapshot,
			// so a running app cannot leave an asset here whose illustration was read before it existed.
			from.setReadOnly(true);
			from.setAutoCommit(false);
			from.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			to.setAutoCommit(false);
			try {
				requireSameMigrations(from, to);
				for (String table : TABLES) {
					rows.put(table, copyTable(from, to, table));
				}
				restartGeneratedIds(to);
				assets.addAll(assetFiles(from));
				to.commit();
			} finally {
				from.rollback();
			}
		}
		return copyMedia(rows, assets, mediaRoot, mediaTarget);
	}

	private static void requireSameMigrations(Connection from, Connection to) throws SQLException {
		long source = latestMigration(from);
		long target = latestMigration(to);
		if (source != target) {
			throw new IllegalStateException("Source schema is at migration " + source + " but this app expects " + target
					+ ". Update the old app or this one first; nothing was changed.");
		}
	}

	private static long latestMigration(Connection connection) throws SQLException {
		long latest = 0;
		try (Statement statement = connection.createStatement(); ResultSet versions = statement.executeQuery(
				"SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL")) {
			while (versions.next()) {
				latest = Math.max(latest, Long.parseLong(versions.getString(1)));
			}
		}
		return latest;
	}

	private static long single(Connection connection, String query) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
			result.next();
			return result.getLong(1);
		}
	}

	private static long copyTable(Connection from, Connection to, String table) throws SQLException {
		List<String> columns = sameColumns(from, to, table);
		String names = String.join(", ", columns);
		String placeholders = columns.stream().map(column -> "?").collect(Collectors.joining(", "));
		long copied = 0;
		try (Statement statement = from.createStatement();
				ResultSet rows = statement.executeQuery("SELECT " + names + " FROM " + table);
				PreparedStatement insert = to.prepareStatement(
						"INSERT INTO " + table + " (" + names + ") VALUES (" + placeholders + ")")) {
			ResultSetMetaData metadata = rows.getMetaData();
			while (rows.next()) {
				for (int column = 1; column <= columns.size(); column++) {
					insert.setObject(column, value(rows, metadata, column));
				}
				insert.addBatch();
				if (++copied % 500 == 0) {
					insert.executeBatch();
				}
			}
			insert.executeBatch();
		}
		long written = count(to, table);
		if (written != copied) {
			throw new IllegalStateException(table + ": read " + copied + " rows but wrote " + written + ".");
		}
		return copied;
	}

	private static Object value(ResultSet rows, ResultSetMetaData metadata, int column) throws SQLException {
		// The wall-clock value, so neither driver shifts it by a time zone on the way across.
		if (metadata.getColumnType(column) == Types.TIMESTAMP) {
			return rows.getObject(column, LocalDateTime.class);
		}
		return rows.getObject(column);
	}

	private static List<String> sameColumns(Connection from, Connection to, String table) throws SQLException {
		Set<String> source = columns(from, table);
		Set<String> target = columns(to, table);
		if (!source.equals(target)) {
			throw new IllegalStateException(table + " has different columns; source " + source + ", app " + target + ".");
		}
		return List.copyOf(source);
	}

	private static Set<String> columns(Connection connection, String table) throws SQLException {
		Set<String> names = new LinkedHashSet<>();
		try (Statement statement = connection.createStatement();
				ResultSet empty = statement.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
			ResultSetMetaData metadata = empty.getMetaData();
			for (int column = 1; column <= metadata.getColumnCount(); column++) {
				names.add(metadata.getColumnName(column).toLowerCase(java.util.Locale.ROOT));
			}
		}
		return names;
	}

	private static long count(Connection connection, String table) throws SQLException {
		return single(connection, "SELECT COUNT(*) FROM " + table);
	}

	/** Explicit ids do not advance the identity counter; without this the next new row would collide. */
	private static void restartGeneratedIds(Connection to) throws SQLException {
		for (String table : GENERATED_ID_TABLES) {
			long next = single(to, "SELECT COALESCE(MAX(id), 0) + 1 FROM " + table);
			try (Statement statement = to.createStatement()) {
				statement.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + next);
			}
		}
	}

	private static List<AssetFile> assetFiles(Connection from) throws SQLException {
		List<AssetFile> files = new ArrayList<>();
		try (Statement statement = from.createStatement();
				ResultSet rows = statement.executeQuery("SELECT storage_key, sha256 FROM asset ORDER BY id")) {
			while (rows.next()) {
				files.add(new AssetFile(rows.getString(1), rows.getString(2)));
			}
		}
		return files;
	}

	private static Result copyMedia(Map<String, Long> rows, List<AssetFile> assets, Path mediaRoot, Path mediaTarget)
			throws IOException {
		Path root = mediaRoot.toAbsolutePath().normalize();
		List<String> problems = new ArrayList<>();
		long files = 0;
		long bytes = 0;
		for (AssetFile asset : assets) {
			Path original = root.resolve(asset.storageKey()).normalize();
			if (!original.startsWith(root)) {
				problems.add("Storage key leaves the media root: " + asset.storageKey());
				continue;
			}
			if (!Files.isRegularFile(original)) {
				problems.add("Missing file: " + asset.storageKey());
				continue;
			}
			String hash = transfer(original, mediaTarget == null ? null : mediaTarget.resolve(asset.storageKey()));
			if (asset.sha256() != null && !asset.sha256().equalsIgnoreCase(hash)) {
				problems.add("Content differs from its recorded SHA-256: " + asset.storageKey());
			}
			files++;
			bytes += Files.size(original);
			Path thumbnail = root.resolve("thumbnails").resolve(asset.storageKey()).normalize();
			if (mediaTarget != null && thumbnail.startsWith(root) && Files.isRegularFile(thumbnail)) {
				transfer(thumbnail, mediaTarget.resolve("thumbnails").resolve(asset.storageKey()));
			}
		}
		return new Result(rows, files, bytes, List.copyOf(problems));
	}

	private static String transfer(Path source, Path destination) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
		try (InputStream input = new DigestInputStream(Files.newInputStream(source), digest)) {
			if (destination == null) {
				input.transferTo(java.io.OutputStream.nullOutputStream());
			} else {
				Files.createDirectories(destination.getParent());
				Files.copy(input, destination);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static boolean isEmptyDirectory(Path path) throws IOException {
		if (!Files.isDirectory(path)) {
			return false;
		}
		try (Stream<Path> entries = Files.list(path)) {
			return entries.findAny().isEmpty();
		}
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> tree = Files.walk(root)) {
			for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	/** {@code --migrate-from-mysql --config=<application-local.properties> [--execute] [--target=<dir>] [--allow-problems]} */
	public static int run(String[] args, PrintStream out) {
		List<String> options = Arrays.asList(args);
		try {
			Path config = Path.of(option(options, "--config=", "config/application-local.properties"));
			Properties settings = new Properties();
			try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
				settings.load(reader);
			}
			String url = required(settings, "ILLUSTRATION_ARCHIVE_DB_URL");
			Path mediaRoot = Path.of(required(settings, "ILLUSTRATION_ARCHIVE_STORAGE_ROOT"));
			DriverManagerDataSource source = new DriverManagerDataSource(url,
					required(settings, "ILLUSTRATION_ARCHIVE_DB_USERNAME"), settings.getProperty("ILLUSTRATION_ARCHIVE_DB_PASSWORD", ""));
			source.setDriverClassName("com.mysql.cj.jdbc.Driver");
			out.println("Source: " + url.split("\\?", 2)[0] + " / " + mediaRoot);
			Result result;
			if (options.contains("--execute")) {
				Path target = Path.of(option(options, "--target=", DesktopServer.defaultNewHome().toString()));
				out.println("Target: " + target);
				result = execute(source, mediaRoot, target, options.contains("--allow-problems"));
			} else {
				out.println("Check only: nothing is written. Add --execute to copy into a new library.");
				result = check(source, mediaRoot);
			}
			result.rows().forEach((table, count) -> out.println("  " + table + ": " + count));
			out.printf("  media files: %d (%.1f MB)%n", result.files(), result.bytes() / 1024.0 / 1024.0);
			result.problems().forEach(problem -> out.println("  problem: " + problem));
			return result.problems().isEmpty() ? 0 : 2;
		} catch (IOException | SQLException | RuntimeException failure) {
			out.println("Failed: " + failure.getMessage());
			return 1;
		}
	}

	private static String option(List<String> options, String prefix, String fallback) {
		return options.stream().filter(option -> option.startsWith(prefix)).findFirst()
				.map(option -> option.substring(prefix.length())).orElse(fallback);
	}

	private static String required(Properties settings, String name) {
		String value = settings.getProperty(name, "").trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException(name + " is missing from the config file.");
		}
		return value;
	}
}
