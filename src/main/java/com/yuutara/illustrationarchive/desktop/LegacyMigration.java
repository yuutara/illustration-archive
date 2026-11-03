package com.yuutara.illustrationarchive.desktop;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Copies a MySQL archive into a desktop library. It is an explicit operation and is built so that neither side can
 * be damaged:
 *
 * <ul>
 * <li>The source is only read: a read-only transaction on MySQL, one snapshot for every table, and media files are
 * opened for reading only. A source at an older supported schema (V7) is upgraded in the copy, never in MySQL.</li>
 * <li>The copy is built in a folder of its own and only used once every row count and file hash checks out. The
 * command line renames it into a new, empty library folder; Settings puts it in {@code <home>/recovery/<id>/incoming},
 * from where {@link LibraryRestore} swaps it for the whole library.</li>
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
	/** Parents before children, so foreign keys hold throughout. A table the source's schema lacks is skipped. */
	private static final List<String> TABLES = List.of("author", "tag", "illustration", "asset", "illustration_tag",
			"x_like_item", "x_like_media", "x_like_sync_state", "x_like_sync_seen_token", "x_like_sync_account");
	private static final List<String> GENERATED_ID_TABLES = List.of("author", "tag", "illustration", "asset",
			"x_like_item", "x_like_media");
	private static final String H2_MIGRATIONS = "classpath:db/migration-h2";
	private static final LibraryBackup.Progress QUIET = (stage, fraction) -> { };

	/**
	 * What was copied.
	 *
	 * @param bytes the originals' size; thumbnails come along but are not counted
	 * @param schemaBefore the source's migration, which it keeps
	 * @param schemaAfter the copy's, after it was upgraded to this app's
	 */
	public record Result(Map<String, Long> rows, long files, long bytes, List<String> problems, String schemaBefore,
			String schemaAfter) {
	}

	private record AssetFile(String storageKey, String sha256) {
	}

	private record Snapshot(Map<String, Long> rows, List<AssetFile> assets, long bytes, String schemaBefore) {
	}

	private LegacyMigration() {
	}

	/** Runs the whole copy against an in-memory H2 and only reads media files. */
	static Result check(DataSource source, Path mediaRoot) throws SQLException, IOException, MaintenanceException {
		String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_DELAY=-1";
		DataSource memory = new DriverManagerDataSource(url, "sa", "");
		try {
			return copy(source, mediaRoot, memory, null, QUIET);
		} finally {
			try (Connection connection = memory.getConnection(); Statement statement = connection.createStatement()) {
				statement.execute("DROP ALL OBJECTS");
			}
		}
	}

	/**
	 * @param allowProblems accept missing or changed media files, which then stay missing in the new library
	 * @throws MaintenanceException when the target is taken or the copy found problems; nothing is left behind
	 */
	static Result execute(DataSource source, Path mediaRoot, Path target, boolean allowProblems)
			throws SQLException, IOException, MaintenanceException {
		Path destination = target.toAbsolutePath().normalize();
		if (Files.exists(destination) && !isEmptyDirectory(destination)) {
			throw new MaintenanceException("目标文件夹已存在且不是空的，所以没有改动任何东西：" + destination);
		}
		Path staging = destination.resolveSibling(destination.getFileName() + ".migrating-"
				+ DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()));
		Files.createDirectories(staging);
		boolean moved = false;
		try {
			Result result = fill(source, mediaRoot, staging, QUIET);
			if (!result.problems().isEmpty() && !allowProblems) {
				throw new MaintenanceException(result.problems().size() + " 个媒体文件有问题，所以没有改动任何东西。第一个："
						+ result.problems().get(0));
			}
			if (Files.exists(destination)) {
				Files.delete(destination);
			}
			LibraryRestore.rename(staging, destination);
			moved = true;
			return result;
		} finally {
			if (!moved) {
				LibraryRestore.deleteTree(staging);
			}
		}
	}

	/**
	 * Copies the old library into {@code incoming} ({@code data} and {@code media}) for Settings to swap in. Media
	 * problems are reported, not refused: the caller decides.
	 *
	 * @throws MaintenanceException with a message that never holds the password, the URL's parameters or user info
	 */
	static Result prepare(Source source, Path mediaRoot, Path incoming, LibraryBackup.Progress progress)
			throws MaintenanceException, IOException {
		try {
			return fill(source.dataSource(), mediaRoot, incoming, progress);
		} catch (SQLException | RuntimeException failed) {
			throw new MaintenanceException(source.redact("没能读取旧版图库的数据库：" + LibraryMaintenance.message(failed)),
					source.scrub(failed));
		}
	}

	private static Result fill(DataSource source, Path mediaRoot, Path folder, LibraryBackup.Progress progress)
			throws SQLException, IOException, MaintenanceException {
		Path media = Files.createDirectories(folder.resolve("media"));
		String url = "jdbc:h2:file:" + folder.resolve("data").resolve("archive").toAbsolutePath() + DesktopServer.H2_COMPATIBILITY
				+ ";DB_CLOSE_ON_EXIT=FALSE";
		return copy(source, mediaRoot, new DriverManagerDataSource(url, "sa", ""), media, progress);
	}

	/**
	 * Builds the copy at the source's migration, fills it from one snapshot, upgrades it to this app's migration, and
	 * then copies (or, without {@code mediaTarget}, only hashes) the media.
	 */
	private static Result copy(DataSource source, Path mediaRoot, DataSource target, Path mediaTarget,
			LibraryBackup.Progress progress) throws SQLException, IOException, MaintenanceException {
		Snapshot snapshot;
		try (Connection from = source.getConnection()) {
			// Read-only first: MySQL refuses to change this inside a transaction. One transaction is one snapshot,
			// so a running app cannot leave an asset here whose illustration was read before it existed.
			from.setReadOnly(true);
			from.setAutoCommit(false);
			from.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			try {
				snapshot = read(from, target, mediaTarget, progress);
			} finally {
				from.rollback();
			}
		}
		Flyway latest = flyway(target).load();
		String schemaAfter = newest(latest).getVersion();
		if (!schemaAfter.equals(snapshot.schemaBefore())) {
			progress.update("正在升级数据库…", 0.32);
			// Validates every applied migration's checksum against this app's before running the missing ones.
			latest.migrate();
			try (Connection to = target.getConnection()) {
				for (Map.Entry<String, Long> table : snapshot.rows().entrySet()) {
					if (count(to, table.getKey()) != table.getValue()) {
						throw new MaintenanceException(table.getKey() + " 升级数据库后行数变了，没有导入。");
					}
				}
			}
		}
		return copyMedia(snapshot, schemaAfter, mediaRoot, mediaTarget, progress);
	}

	private static Snapshot read(Connection from, DataSource target, Path mediaTarget, LibraryBackup.Progress progress)
			throws SQLException, MaintenanceException {
		progress.update("正在读取旧版图库的数据库…", 0);
		String schema = sourceSchema(from, flyway(target).load());
		long bytes = single(from, "SELECT COALESCE(SUM(file_size), 0) FROM asset");
		if (mediaTarget != null) {
			// Thumbnails and the database come on top of the originals; a quarter more covers them.
			LibraryBackup.requireSpace(mediaTarget, bytes + bytes / 4);
		}
		flyway(target).target(MigrationVersion.fromVersion(schema)).load().migrate();
		Map<String, Long> rows = new LinkedHashMap<>();
		List<AssetFile> assets;
		try (Connection to = target.getConnection()) {
			to.setAutoCommit(false);
			Set<String> present = tables(to);
			List<String> tables = TABLES.stream().filter(present::contains).toList();
			for (String table : tables) {
				if (progress.cancelled()) {
					throw MaintenanceException.cancelled();
				}
				progress.update("正在读取旧版图库的数据库…", 0.3 * rows.size() / tables.size());
				rows.put(table, copyTable(from, to, table));
			}
			restartGeneratedIds(to);
			assets = assetFiles(from);
			to.commit();
		}
		return new Snapshot(rows, assets, bytes, schema);
	}

	private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(DataSource target) {
		return Flyway.configure().dataSource(target).locations(H2_MIGRATIONS);
	}

	private static MigrationVersion newest(Flyway flyway) {
		return Arrays.stream(flyway.info().all()).map(MigrationInfo::getVersion).filter(Objects::nonNull)
				.max(Comparator.naturalOrder()).orElseThrow();
	}

	/**
	 * The source's migration, once its history is one this app can take: every migration up to it succeeded, and
	 * each is one this app knows. A newer, unknown or failed one is refused, since the copy would not match it.
	 */
	private static String sourceSchema(Connection from, Flyway app) throws MaintenanceException {
		List<MigrationVersion> known = Arrays.stream(app.info().all()).map(MigrationInfo::getVersion)
				.filter(Objects::nonNull).sorted().toList();
		TreeSet<MigrationVersion> applied = new TreeSet<>();
		try (Statement statement = from.createStatement(); ResultSet versions = statement.executeQuery(
				"SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL")) {
			while (versions.next()) {
				if (!versions.getBoolean(2)) {
					throw new MaintenanceException("旧版图库的数据库有一次没有完成的升级（V" + versions.getString(1)
							+ "），不能导入。请先在旧版里处理好再试。");
				}
				applied.add(MigrationVersion.fromVersion(versions.getString(1)));
			}
		} catch (SQLException | RuntimeException unreadable) {
			throw new MaintenanceException("这个数据库读不到插画档案的升级记录，不像是插画档案的图库，没有导入。", unreadable);
		}
		if (applied.isEmpty()) {
			throw new MaintenanceException("这个数据库还没有插画档案的表，没有导入。");
		}
		MigrationVersion latest = applied.last();
		MigrationVersion newest = known.get(known.size() - 1);
		if (latest.compareTo(newest) > 0) {
			throw new MaintenanceException("旧版图库来自更新版本的插画档案（数据库版本 V" + latest.getVersion()
					+ "），请先升级本应用再导入。");
		}
		Set<MigrationVersion> expected = known.stream().filter(version -> version.compareTo(latest) <= 0)
				.collect(Collectors.toCollection(TreeSet::new));
		if (!applied.equals(expected)) {
			throw new MaintenanceException("旧版图库的数据库升级记录与本应用对不上（有 " + names(applied) + "，应为 "
					+ names(expected) + "），没有导入。");
		}
		return latest.getVersion();
	}

	private static String names(Set<MigrationVersion> versions) {
		return versions.stream().map(version -> "V" + version.getVersion()).collect(Collectors.joining("、"));
	}

	private static Set<String> tables(Connection connection) throws SQLException {
		Set<String> names = new LinkedHashSet<>();
		try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
			while (tables.next()) {
				names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
			}
		}
		return names;
	}

	private static long single(Connection connection, String query) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
			result.next();
			return result.getLong(1);
		}
	}

	private static long copyTable(Connection from, Connection to, String table) throws SQLException, MaintenanceException {
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
			throw new MaintenanceException(table + "：读到 " + copied + " 行，却写入了 " + written + " 行，没有导入。");
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

	private static List<String> sameColumns(Connection from, Connection to, String table)
			throws SQLException, MaintenanceException {
		Set<String> source;
		try {
			source = columns(from, table);
		} catch (SQLException missing) {
			throw new MaintenanceException("旧版图库缺少表 " + table + "，与它记录的数据库版本不符，没有导入。", missing);
		}
		Set<String> target = columns(to, table);
		if (!source.equals(target)) {
			throw new MaintenanceException("旧版图库的表 " + table + " 的列与本应用不同（旧版 " + source + "，本应用 " + target
					+ "），没有导入。");
		}
		return List.copyOf(source);
	}

	private static Set<String> columns(Connection connection, String table) throws SQLException {
		Set<String> names = new LinkedHashSet<>();
		try (Statement statement = connection.createStatement();
				ResultSet empty = statement.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
			ResultSetMetaData metadata = empty.getMetaData();
			for (int column = 1; column <= metadata.getColumnCount(); column++) {
				names.add(metadata.getColumnName(column).toLowerCase(Locale.ROOT));
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

	private static Result copyMedia(Snapshot snapshot, String schemaAfter, Path mediaRoot, Path mediaTarget,
			LibraryBackup.Progress progress) throws IOException, MaintenanceException {
		Path root = mediaRoot.toAbsolutePath().normalize();
		List<String> problems = new ArrayList<>();
		long files = 0;
		long bytes = 0;
		LibraryBackup.Meter meter = new LibraryBackup.Meter(progress,
				mediaTarget == null ? "正在核对媒体文件…" : "正在复制并核对媒体文件…", snapshot.bytes(), 0.35, 1);
		for (AssetFile asset : snapshot.assets()) {
			Path original = root.resolve(asset.storageKey()).normalize();
			if (!original.startsWith(root)) {
				problems.add("存储路径超出了媒体文件夹：" + asset.storageKey());
				continue;
			}
			if (!Files.isRegularFile(original)) {
				problems.add("缺少文件：" + asset.storageKey());
				continue;
			}
			String hash = transfer(original, mediaTarget == null ? null : mediaTarget.resolve(asset.storageKey()));
			if (asset.sha256() != null && !asset.sha256().equalsIgnoreCase(hash)) {
				problems.add("内容与记录的 SHA-256 不符：" + asset.storageKey());
			}
			long size = Files.size(original);
			files++;
			bytes += size;
			Path thumbnail = root.resolve("thumbnails").resolve(asset.storageKey()).normalize();
			if (mediaTarget != null && thumbnail.startsWith(root) && Files.isRegularFile(thumbnail)) {
				transfer(thumbnail, mediaTarget.resolve("thumbnails").resolve(asset.storageKey()));
			}
			meter.add(size);
		}
		return new Result(snapshot.rows(), files, bytes, List.copyOf(problems), snapshot.schemaBefore(), schemaAfter);
	}

	private static String transfer(Path source, Path destination) throws IOException {
		MessageDigest digest = LibraryBackup.sha256();
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

	/**
	 * The old server app's MySQL connection and media folder, from its {@code application-local.properties}. The file
	 * is a trust boundary: only five keys are read, only a MySQL URL and local media are accepted.
	 *
	 * <p>The URL is held to one plain form, {@code jdbc:mysql://host[:port][,host[:port]…]/database[?name=value&…]},
	 * and anything else is refused before connecting. The driver accepts much more (user info, {@code address=(…)}
	 * hosts with their own user and password, percent-encoded names, spaces, dozens of properties whose values it
	 * repeats in its errors), and none of it can be told apart from a secret reliably. So the URL holds no credential
	 * at all: user name and password come only from their own keys, the hosts and database are plain names, and each
	 * parameter is one of {@link #PARAMETERS} with a value of a fixed shape. What this object says about itself,
	 * {@link #describe} and {@link #redact}, therefore never holds the password, and nothing the driver echoes from
	 * the URL is a secret.
	 */
	static final class Source {

		static final String URL = "ILLUSTRATION_ARCHIVE_DB_URL";
		static final String USERNAME = "ILLUSTRATION_ARCHIVE_DB_USERNAME";
		static final String PASSWORD = "ILLUSTRATION_ARCHIVE_DB_PASSWORD";
		static final String STORAGE_ROOT = "ILLUSTRATION_ARCHIVE_STORAGE_ROOT";
		static final String STORAGE_TYPE = "ILLUSTRATION_ARCHIVE_STORAGE_TYPE";
		private static final Pattern PASSWORD_SETTING = Pattern.compile("(?i)(password|pwd)=[^\\s)&;,]*");
		private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
		private static final String HOST = "(?:[A-Za-z0-9][A-Za-z0-9.-]{0,252}|\\[[0-9A-Fa-f:.]{2,45}\\])(?::[0-9]{1,5})?";
		private static final Pattern PLAIN_URL = Pattern.compile(
				"jdbc:mysql://(" + HOST + "(?:," + HOST + ")*)/([A-Za-z0-9_$-]{1,64})(?:\\?(.+))?");
		private static final Pattern BOOLEAN = Pattern.compile("(?i)true|false|yes|no");
		private static final Pattern NUMBER = Pattern.compile("[0-9]{1,9}");
		/**
		 * The parameters an old config may set, each with the shape of its value: what the server app's own example and
		 * Compose file use, and what a local MySQL 8 needs to connect. Names match case-insensitively.
		 */
		static final Map<String, Pattern> PARAMETERS = Map.ofEntries(
				Map.entry("useunicode", BOOLEAN),
				Map.entry("characterencoding", Pattern.compile("[A-Za-z0-9_-]{1,32}")),
				Map.entry("servertimezone", Pattern.compile("[A-Za-z0-9/_+:-]{1,64}")),
				Map.entry("connectiontimezone", Pattern.compile("[A-Za-z0-9/_+:-]{1,64}")),
				Map.entry("usessl", BOOLEAN),
				Map.entry("requiressl", BOOLEAN),
				Map.entry("verifyservercertificate", BOOLEAN),
				Map.entry("sslmode", Pattern.compile("(?i)DISABLED|PREFERRED|REQUIRED|VERIFY_CA|VERIFY_IDENTITY")),
				Map.entry("allowpublickeyretrieval", BOOLEAN),
				Map.entry("zerodatetimebehavior", Pattern.compile("(?i)CONVERT_TO_NULL|EXCEPTION|ROUND")),
				Map.entry("connecttimeout", NUMBER),
				Map.entry("sockettimeout", NUMBER));

		private final String host;
		private final String database;
		private final String storageRoot;
		private final DataSource dataSource;
		private final List<String> secrets;

		/** @param secrets texts that must not appear in a message, such as the password or the URL's parameters */
		Source(String host, String database, String storageRoot, DataSource dataSource, List<String> secrets) {
			this.host = host;
			this.database = database;
			this.storageRoot = storageRoot;
			this.dataSource = dataSource;
			// Longest first, so a secret that contains another is replaced whole.
			this.secrets = secrets.stream().filter(secret -> secret != null && !secret.isEmpty())
					.sorted(Comparator.comparingInt(String::length).reversed()).toList();
		}

		static Source read(Path config) throws MaintenanceException {
			Properties settings = new Properties();
			try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
				settings.load(reader);
			} catch (IOException | IllegalArgumentException unreadable) {
				throw new MaintenanceException("读不到这个配置文件（应为旧版的 application-local.properties，UTF-8 编码）："
						+ config.getFileName(), unreadable);
			}
			String url = required(settings, URL);
			if (!url.startsWith("jdbc:mysql:")) {
				throw new MaintenanceException("配置文件里的数据库不是 MySQL 地址（" + URL + " 应以 jdbc:mysql:// 开头）。"
						+ "只能从旧版服务端的 MySQL 图库导入。");
			}
			Matcher plain = PLAIN_URL.matcher(url);
			if (!plain.matches()) {
				// Not echoed: whatever does not fit may be exactly where a password was written.
				throw new MaintenanceException("配置文件里的数据库地址不是本应用支持的写法，没有连接。只支持 "
						+ "jdbc:mysql://主机[:端口]/库名[?参数=值&…]：地址里不能有用户名或密码（如 用户:密码@、address=(…)）、"
						+ "空格或 % 编码；用户名和密码请写在 " + USERNAME + " 和 " + PASSWORD + " 里。");
			}
			if (plain.group(3) != null) {
				String[] parameters = plain.group(3).split("&", -1);
				for (int index = 0; index < parameters.length; index++) {
					String[] pair = parameters[index].split("=", -1);
					Pattern shape = pair.length == 2 ? PARAMETERS.get(pair[0].toLowerCase(Locale.ROOT)) : null;
					if (shape == null || !shape.matcher(pair[1]).matches()) {
						throw new MaintenanceException("配置文件里数据库地址的第 " + (index + 1) + " 个参数不受支持或值的格式不对，没有连接。"
								+ "只能用 useUnicode、characterEncoding、serverTimezone、connectionTimeZone、useSSL、requireSSL、"
								+ "verifyServerCertificate、sslMode、allowPublicKeyRetrieval、zeroDateTimeBehavior、connectTimeout、"
								+ "socketTimeout；用户名和密码请写在 " + USERNAME + " 和 " + PASSWORD + " 里。");
					}
				}
			}
			String type = settings.getProperty(STORAGE_TYPE, "").trim();
			if (!type.isEmpty() && !type.equalsIgnoreCase("local")) {
				throw new MaintenanceException("旧版图库的媒体不在本机文件夹里（" + STORAGE_TYPE + " 不是 local），"
						+ "例如存在 S3 上。暂时只能导入媒体在本机文件夹里的图库。");
			}
			String username = required(settings, USERNAME);
			String password = settings.getProperty(PASSWORD, "");
			String root = required(settings, STORAGE_ROOT);

			return new Source(plain.group(1), plain.group(2), root, connect(url, username, password), List.of(password));
		}

		private static String required(Properties settings, String name) throws MaintenanceException {
			String value = settings.getProperty(name, "").trim();
			if (value.isEmpty()) {
				throw new MaintenanceException("配置文件里没有 " + name + "，不像是旧版的 application-local.properties。");
			}
			return value;
		}

		/** Connects through the driver itself, so nothing on the way (such as a pool or its logging) sees the password. */
		private static DataSource connect(String url, String username, String password) {
			return new AbstractDataSource() {
				@Override
				public Connection getConnection() throws SQLException {
					Driver driver;
					try {
						driver = (Driver) Class.forName("com.mysql.cj.jdbc.Driver", true, LegacyMigration.class.getClassLoader())
								.getDeclaredConstructor().newInstance();
					} catch (ReflectiveOperationException missing) {
						throw new SQLException("The MySQL driver is not available.", missing);
					}
					Properties info = new Properties();
					info.setProperty("user", username);
					info.setProperty("password", password);
					info.setProperty("connectTimeout", Integer.toString(CONNECT_TIMEOUT_MILLIS));
					Connection connection = driver.connect(url, info);
					if (connection == null) {
						throw new SQLException("The MySQL driver did not accept the database URL.");
					}
					return connection;
				}

				@Override
				public Connection getConnection(String username, String password) {
					throw new UnsupportedOperationException();
				}
			};
		}

		DataSource dataSource() {
			return dataSource;
		}

		String host() {
			return host;
		}

		String database() {
			return database;
		}

		/** For the user: host and database, nothing that signs in. */
		String describe() {
			return host + (database.isEmpty() ? "" : " / " + database);
		}

		/** The media folder as written; only an absolute one is used as it is, a relative one is not guessed. */
		String configuredMediaRoot() {
			return storageRoot;
		}

		Optional<Path> mediaRoot() {
			try {
				Path root = Path.of(storageRoot);
				return root.isAbsolute() ? Optional.of(root.normalize()) : Optional.empty();
			} catch (InvalidPathException unusable) {
				return Optional.empty();
			}
		}

		String redact(String text) {
			if (text == null) {
				return null;
			}
			String redacted = text;
			for (String secret : secrets) {
				redacted = redacted.replace(secret, "***");
			}
			return PASSWORD_SETTING.matcher(redacted).replaceAll("$1=***");
		}

		/** The failure with the same classes and stack traces, for the log, but with every message redacted. */
		Throwable scrub(Throwable failure) {
			if (failure == null) {
				return null;
			}
			Throwable cause = failure.getCause() == failure ? null : scrub(failure.getCause());
			Exception copy = new Exception(failure.getClass().getName() + ": " + redact(failure.getMessage()), cause);
			copy.setStackTrace(failure.getStackTrace());
			return copy;
		}

		@Override
		public String toString() {
			return describe();
		}
	}

	/** {@code --migrate-from-mysql --config=<application-local.properties> [--execute] [--target=<dir>] [--allow-problems]} */
	public static int run(String[] args, PrintStream out) {
		List<String> options = Arrays.asList(args);
		Source source = null;
		try {
			source = Source.read(Path.of(option(options, "--config=", "config/application-local.properties")));
			// The command line resolves a relative folder as before, against where it runs.
			Path mediaRoot = Path.of(source.configuredMediaRoot());
			out.println("Source: " + source.describe() + " / " + mediaRoot);
			Result result;
			if (options.contains("--execute")) {
				Path target = Path.of(option(options, "--target=", DesktopServer.defaultNewHome().toString()));
				out.println("Target: " + target);
				result = execute(source.dataSource(), mediaRoot, target, options.contains("--allow-problems"));
			} else {
				out.println("Check only: nothing is written. Add --execute to copy into a new library.");
				result = check(source.dataSource(), mediaRoot);
			}
			out.println("  schema: V" + result.schemaBefore() + (result.schemaBefore().equals(result.schemaAfter()) ? ""
					: " -> V" + result.schemaAfter() + " (upgraded in the copy only)"));
			result.rows().forEach((table, count) -> out.println("  " + table + ": " + count));
			out.printf("  media files: %d (%.1f MB)%n", result.files(), result.bytes() / 1024.0 / 1024.0);
			result.problems().forEach(problem -> out.println("  problem: " + problem));
			return result.problems().isEmpty() ? 0 : 2;
		} catch (IOException | SQLException | MaintenanceException | RuntimeException failure) {
			String message = LibraryMaintenance.message(failure);
			out.println("Failed: " + (source == null ? message : source.redact(message)));
			return 1;
		}
	}

	private static String option(List<String> options, String prefix, String fallback) {
		return options.stream().filter(option -> option.startsWith(prefix)).findFirst()
				.map(option -> option.substring(prefix.length())).orElse(fallback);
	}
}
