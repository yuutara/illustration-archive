package com.yuutara.illustrationarchive.desktop;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The library backup: one ZIP with the database, the archived media and a manifest, made from a closed library.
 *
 * <p>What goes in is picked by name: {@code data/archive.mv.db}, every original the database refers to, and the
 * thumbnails that exist for them. Settings, credentials, logs and the window's browser profile are never read, so a
 * backup cannot carry an X login or an API key. The manifest records each entry's size and SHA-256 and the database's
 * migration history, and no machine paths.
 *
 * <p>{@link #read} is the only way back in. A ZIP comes from outside, so neither its headers nor its manifest are
 * trusted: names must be plain relative paths, every entry must be listed and hash to what is listed, and nothing is
 * read past its declared size. The same check runs on a new backup before it is published.
 */
final class LibraryBackup {

	static final int FORMAT = 1;
	static final String MANIFEST = "manifest.json";
	static final String DATABASE = "data/archive.mv.db";
	static final String MEDIA = "media/";
	static final String THUMBNAILS = "thumbnails/";
	static final int MAX_ENTRIES = 500_000;
	static final long MAX_MANIFEST_BYTES = 64L << 20;
	/** Kept free beyond the unpacked files: the database grows a little when it is opened or upgraded. */
	static final long SPACE_MARGIN = 64L << 20;
	private static final int MAX_NAME_LENGTH = 400;
	private static final int BUFFER = 1 << 16;
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");
	private static final String FORBIDDEN = "<>:\"/\\|?*";
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	record Migration(String version, Integer checksum) {
	}

	/** Rows of the main tables, and the files and bytes in the backup. */
	record Summary(long works, long assets, long authors, long tags, long inbox, long files, long bytes) {
	}

	record Entry(String path, long size, String sha256) {
	}

	record Manifest(int format, String createdAt, String appVersion, String h2Version, List<Migration> migrations,
			Summary summary, List<Entry> entries) {
	}

	/** What a database says about itself; {@code counts} leaves files and bytes at zero. */
	record Facts(String h2Version, List<Migration> migrations, Summary counts, List<Asset> assets) {
	}

	record Asset(String storageKey, String sha256) {
	}

	private record Source(String name, Path file, String expectedSha256) {
	}

	interface Progress {

		/** @param fraction between 0 and 1, or negative while the length of the stage is unknown */
		void update(String stage, double fraction);

		default boolean cancelled() {
			return false;
		}
	}

	private LibraryBackup() {
	}

	static String suggestedName() {
		return "插画档案备份-" + FILE_TIME.format(LocalDateTime.now()) + ".zip";
	}

	/** @return where the backup will be written: outside the library, under a name nothing has yet */
	static Path checkDestination(Path home, Path chosen) throws MaintenanceException {
		Path target = chosen.toAbsolutePath().normalize();
		if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
			target = target.resolveSibling(target.getFileName() + ".zip");
		}
		Path folder = target.getParent();
		if (folder == null || !Files.isDirectory(folder)) {
			throw new MaintenanceException("保存备份的文件夹不存在：" + folder);
		}
		try {
			// Real paths, so a junction or link into the library counts as inside it.
			if (folder.toRealPath().startsWith(home.toRealPath())) {
				throw new MaintenanceException("备份不能保存在数据文件夹里，请换个位置，例如“文档”或移动硬盘。");
			}
		} catch (IOException unreadable) {
			throw new MaintenanceException("无法访问保存位置：" + folder, unreadable);
		}
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new MaintenanceException("那里已有同名文件，备份不会覆盖它，请换个文件名。");
		}
		return target;
	}

	/**
	 * Writes a backup of a closed library. The ZIP is built next to the destination under a temporary name, read back
	 * in full, and only then given its name; a failed or cancelled run leaves nothing there.
	 */
	static Manifest create(Path home, Path destination, String appVersion, Progress progress) throws MaintenanceException {
		return createAt(home, checkDestination(home, destination), appVersion, progress);
	}

	/**
	 * {@link #create} without the destination check, for the app's own backups: {@code target} must be a new name in an
	 * existing folder. The library's own folders are never read for anything but its database and media, so a backup
	 * kept in the home does not end up in later backups.
	 */
	static Manifest createAt(Path home, Path target, String appVersion, Progress progress) throws MaintenanceException {
		progress.update("正在读取图库…", -1);
		Facts facts = facts(home.resolve("data"));
		List<Source> sources = sources(home, facts);
		Path partial = null;
		try {
			long total = 0;
			for (Source source : sources) {
				total += Files.size(source.file());
			}
			requireSpace(target.getParent(), total);
			partial =Files.createTempFile(target.getParent(), ".illustration-archive-backup-", ".partial");
			Manifest manifest = write(partial, sources, facts, appVersion, new Meter(progress, "正在打包作品…", total, 0, 0.55));
			read(partial, null, progress, "正在校验备份…", 0.55, 1);
			if (progress.cancelled()) {
				throw MaintenanceException.cancelled();
			}
			Files.move(partial, target);
			partial = null;
			return manifest;
		} catch (FileAlreadyExistsException taken) {
			throw new MaintenanceException("那里已有同名文件，备份不会覆盖它，请换个文件名。", taken);
		} catch (IOException failed) {
			throw new MaintenanceException("写入备份文件失败：" + reason(failed), failed);
		} finally {
			if (partial != null) {
				deleteQuietly(partial);
			}
		}
	}

	private static List<Source> sources(Path home, Facts facts) throws MaintenanceException {
		List<Source> sources = new ArrayList<>();
		Path data = home.resolve("data");
		Path database = regularInside(data, "archive.mv.db");
		if (database == null) {
			throw new MaintenanceException("找不到图库数据库：" + data.resolve("archive.mv.db"));
		}
		sources.add(new Source(DATABASE, database, null));
		Path media = home.resolve("media");
		List<String> missing = new ArrayList<>();
		for (Asset asset : facts.assets()) {
			String key = asset.storageKey();
			if (!safeName(MEDIA + key) || !safeName(MEDIA + THUMBNAILS + key)) {
				throw new MaintenanceException("作品记录里有无法备份的文件路径：" + key);
			}
			Path original = regularInside(media, key);
			if (original == null) {
				missing.add(key);
				continue;
			}
			sources.add(new Source(MEDIA + key, original, asset.sha256()));
			// A missing thumbnail is made again when it is first shown, as it is today.
			Path thumbnail = regularInside(media, THUMBNAILS + key);
			if (thumbnail != null) {
				sources.add(new Source(MEDIA + THUMBNAILS + key, thumbnail, null));
			}
		}
		if (!missing.isEmpty()) {
			throw new MaintenanceException(missing.size() + " 张原图不在数据文件夹里（例如 " + missing.get(0) + "），所以没有生成备份。");
		}
		return sources;
	}

	/** @return the file, if it is a plain file that really lies under {@code root}, links resolved */
	private static Path regularInside(Path root, String relative) throws MaintenanceException {
		Path file = root.resolve(relative).normalize();
		if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		try {
			return file.toRealPath().startsWith(root.toRealPath()) ? file : null;
		} catch (IOException unreadable) {
			throw new MaintenanceException("无法读取 " + file, unreadable);
		}
	}

	private static Manifest write(Path partial, List<Source> sources, Facts facts, String appVersion, Meter meter)
			throws IOException, MaintenanceException {
		List<Entry> entries = new ArrayList<>(sources.size());
		byte[] buffer = new byte[BUFFER];
		try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE);
				ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel), BUFFER),
						StandardCharsets.UTF_8)) {
			for (Source source : sources) {
				// Pictures and video are compressed already; only the database gains from deflating.
				zip.setLevel(source.name().equals(DATABASE) ? Deflater.BEST_SPEED : Deflater.NO_COMPRESSION);
				zip.putNextEntry(new ZipEntry(source.name()));
				MessageDigest digest = sha256();
				long size = 0;
				try (InputStream input = Files.newInputStream(source.file())) {
					for (int read; (read = input.read(buffer)) > 0; ) {
						digest.update(buffer, 0, read);
						zip.write(buffer, 0, read);
						size += read;
						meter.add(read);
					}
				}
				zip.closeEntry();
				String hash = HexFormat.of().formatHex(digest.digest());
				if (source.expectedSha256() != null && !source.expectedSha256().equalsIgnoreCase(hash)) {
					throw new MaintenanceException("有原图的内容与入库时记录的不一致（" + source.name().substring(MEDIA.length())
							+ "），所以没有生成备份。");
				}
				entries.add(new Entry(source.name(), size, hash));
			}
			long bytes = entries.stream().mapToLong(Entry::size).sum();
			Summary counts = facts.counts();
			Manifest manifest = new Manifest(FORMAT, OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
					appVersion, facts.h2Version(), facts.migrations(), new Summary(counts.works(), counts.assets(),
							counts.authors(), counts.tags(), counts.inbox(), entries.size(), bytes), List.copyOf(entries));
			zip.setLevel(Deflater.DEFAULT_COMPRESSION);
			zip.putNextEntry(new ZipEntry(MANIFEST));
			zip.write(JSON.writeValueAsBytes(manifest));
			zip.closeEntry();
			zip.finish();
			zip.flush();
			channel.force(true);
			return manifest;
		}
	}

	/** An existing library database; a missing one is an error here, never a new empty database. */
	static String url(Path data) {
		return "jdbc:h2:file:" + data.toAbsolutePath().normalize().resolve("archive") + DesktopServer.H2_COMPATIBILITY
				+ ";DB_CLOSE_ON_EXIT=FALSE;IFEXISTS=TRUE";
	}

	static Connection open(Path data) throws SQLException {
		return DriverManager.getConnection(url(data), "sa", "");
	}

	/** Reads a library that must be closed: any other session means something still has it open. */
	static Facts facts(Path data) throws MaintenanceException {
		try (Connection connection = open(data)) {
			if (single(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS") != 1) {
				throw new MaintenanceException("图库数据库仍在使用中，所以没有继续。");
			}
			return facts(connection);
		} catch (SQLException failed) {
			throw new MaintenanceException("没能读取图库数据库：" + failed.getMessage(), failed);
		}
	}

	static Facts facts(Connection connection) throws SQLException {
		String h2Version = connection.getMetaData().getDatabaseProductVersion().split(" ")[0];
		List<Migration> migrations = new ArrayList<>();
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
				"SELECT version, checksum FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank")) {
			while (rows.next()) {
				migrations.add(new Migration(rows.getString(1), rows.getObject(2, Integer.class)));
			}
		}
		Summary counts = new Summary(single(connection, "SELECT COUNT(*) FROM illustration"),
				single(connection, "SELECT COUNT(*) FROM asset"), single(connection, "SELECT COUNT(*) FROM author"),
				single(connection, "SELECT COUNT(*) FROM tag"), single(connection, "SELECT COUNT(*) FROM x_like_item"), 0, 0);
		List<Asset> assets = new ArrayList<>();
		try (Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT storage_key, sha256 FROM asset ORDER BY id")) {
			while (rows.next()) {
				assets.add(new Asset(rows.getString(1), rows.getString(2)));
			}
		}
		return new Facts(h2Version, List.copyOf(migrations), counts, List.copyOf(assets));
	}

	private static long single(Connection connection, String query) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
			result.next();
			return result.getLong(1);
		}
	}

	/**
	 * Checks a backup completely and, given {@code extractTo}, unpacks it there at the same time.
	 *
	 * @param extractTo an empty folder on the disk that will hold the files, or null to only check
	 * @return the manifest, once every entry has been read in full and matched it
	 */
	static Manifest read(Path file, Path extractTo, Progress progress, String stage, double from, double to)
			throws MaintenanceException {
		try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
			Map<String, ZipEntry> named = new HashMap<>();
			Set<String> folded = new HashSet<>();
			Enumeration<? extends ZipEntry> all = zip.entries();
			while (all.hasMoreElements()) {
				ZipEntry entry = all.nextElement();
				String name = entry.getName();
				if (named.size() > MAX_ENTRIES) {
					throw notABackup("条目过多");
				}
				if (!name.equals(MANIFEST) && !safeName(name)) {
					throw notABackup("含有不允许的路径 " + name);
				}
				// Windows names ignore case, so two entries differing only in case would land on one file.
				if (named.putIfAbsent(name, entry) != null || !folded.add(name.toLowerCase(Locale.ROOT))) {
					throw notABackup("含有重复的条目 " + name);
				}
			}
			ZipEntry listing = named.get(MANIFEST);
			if (listing == null) {
				throw notABackup("缺少清单");
			}
			Manifest manifest = manifest(zip, listing);
			Set<String> listed = new HashSet<>();
			long total = 0;
			for (Entry entry : manifest.entries()) {
				if (entry == null || entry.path() == null || !safeName(entry.path()) || entry.size() < 0
						|| entry.sha256() == null || !SHA256.matcher(entry.sha256()).matches() || !listed.add(entry.path())) {
					throw notABackup("清单里有无效的条目");
				}
				if (!named.containsKey(entry.path())) {
					throw notABackup("缺少 " + entry.path());
				}
				total = Math.addExact(total, entry.size());
			}
			if (!listed.contains(DATABASE)) {
				throw notABackup("缺少数据库");
			}
			if (named.size() != listed.size() + 1) {
				throw notABackup("含有清单以外的文件");
			}
			if (extractTo != null) {
				requireSpace(extractTo, total);
			}
			Meter meter = new Meter(progress, stage, total, from, to);
			byte[] buffer = new byte[BUFFER];
			for (Entry entry : manifest.entries()) {
				// safeName() keeps every path below extractTo.
				Path target = extractTo == null ? null : extractTo.resolve(entry.path());
				if (target != null) {
					Files.createDirectories(target.getParent());
				}
				MessageDigest digest = sha256();
				long size = 0;
				try (InputStream input = zip.getInputStream(named.get(entry.path()));
						OutputStream output = target == null ? OutputStream.nullOutputStream()
								: Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
					for (int read; (read = input.read(buffer)) > 0; ) {
						size += read;
						if (size > entry.size()) {
							throw notABackup(entry.path() + " 比清单记录的大");
						}
						digest.update(buffer, 0, read);
						output.write(buffer, 0, read);
						meter.add(read);
					}
				}
				if (size != entry.size() || !HexFormat.of().formatHex(digest.digest()).equals(entry.sha256())) {
					throw notABackup(entry.path() + " 与清单不符");
				}
			}
			return manifest;
		} catch (ZipException | JacksonException | ArithmeticException corrupt) {
			throw new MaintenanceException("这个文件不是可用的插画档案备份，或已经损坏。", corrupt);
		} catch (IOException failed) {
			throw new MaintenanceException("读取备份失败：" + reason(failed), failed);
		}
	}

	private static Manifest manifest(ZipFile zip, ZipEntry listing) throws IOException, MaintenanceException {
		byte[] json;
		try (InputStream input = zip.getInputStream(listing)) {
			json = input.readNBytes((int) MAX_MANIFEST_BYTES + 1);
		}
		if (json.length > MAX_MANIFEST_BYTES) {
			throw notABackup("清单过大");
		}
		Manifest manifest = JSON.readValue(json, Manifest.class);
		if (manifest == null) {
			throw notABackup("清单为空");
		}
		if (manifest.format() > FORMAT) {
			throw new MaintenanceException("这个备份来自更新版本的插画档案，请先升级本应用再恢复。");
		}
		if (manifest.format() != FORMAT || manifest.h2Version() == null || manifest.migrations() == null
				|| manifest.summary() == null || manifest.entries() == null || manifest.entries().size() > MAX_ENTRIES) {
			throw notABackup("清单不完整");
		}
		return manifest;
	}

	private static MaintenanceException notABackup(String detail) {
		return new MaintenanceException("这个文件不是可用的插画档案备份，或已经损坏（" + detail + "）。");
	}

	/**
	 * A ZIP entry name this format writes: the database, or a file under {@code media/}, as plain relative segments
	 * that Windows stores under exactly that name.
	 */
	static boolean safeName(String name) {
		if (name.length() > MAX_NAME_LENGTH || !(name.equals(DATABASE) || name.startsWith(MEDIA))) {
			return false;
		}
		String[] segments = name.split("/", -1);
		for (String segment : segments) {
			if (segment.isEmpty() || segment.endsWith(".") || segment.endsWith(" ") || DEVICE.matcher(segment).matches()) {
				return false;
			}
			for (int i = 0; i < segment.length(); i++) {
				char character = segment.charAt(i);
				if (character < 0x20 || FORBIDDEN.indexOf(character) >= 0) {
					return false;
				}
			}
		}
		return segments.length >= 2;
	}

	static void requireSpace(Path folder, long bytes) throws MaintenanceException {
		try {
			long usable = Files.getFileStore(folder).getUsableSpace();
			// Compared by subtraction: the sizes come from a manifest, and bytes + margin could wrap around.
			if (bytes < 0 || bytes > usable - SPACE_MARGIN) {
				long needed = bytes < 0 || bytes > Long.MAX_VALUE - SPACE_MARGIN ? Long.MAX_VALUE : bytes + SPACE_MARGIN;
				throw new MaintenanceException("磁盘空间不够：需要约 " + size(needed) + "，可用 " + size(usable)
						+ "。当前图库没有任何改动。");
			}
		} catch (IOException unreadable) {
			throw new MaintenanceException("无法读取磁盘剩余空间：" + reason(unreadable), unreadable);
		}
	}

	static String size(long bytes) {
		if (bytes < 1024 * 1024) {
			return Math.max(1, (bytes + 1023) / 1024) + " KB";
		}
		double megabytes = bytes / 1024.0 / 1024.0;
		return megabytes < 1024 ? String.format(Locale.ROOT, "%.1f MB", megabytes)
				: String.format(Locale.ROOT, "%.2f GB", megabytes / 1024);
	}

	static String reason(IOException failure) {
		return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
	}

	static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}

	static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// A leftover temporary file is harmless and named as such.
		}
	}

	/** Reports bytes done as the share of one stage, a little at a time, and is where a cancel takes effect. */
	static final class Meter {

		private final Progress progress;
		private final String stage;
		private final long total;
		private final double from;
		private final double to;
		private long done;
		private long step = -1;

		Meter(Progress progress, String stage, long total, double from, double to) {
			this.progress = progress;
			this.stage = stage;
			this.total = Math.max(1, total);
			this.from = from;
			this.to = to;
			report();
		}

		void add(long bytes) throws MaintenanceException {
			if (progress.cancelled()) {
				throw MaintenanceException.cancelled();
			}
			done += bytes;
			report();
		}

		private void report() {
			long now = done * 200 / total;
			if (now != step) {
				step = now;
				progress.update(stage, from + (to - from) * Math.min(1, done / (double) total));
			}
		}
	}
}
