package com.yuutara.illustrationarchive.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Same contract for local files and S3 adapter; S3 transport is mocked, not real S3 acceptance. */
class AnimatedStorageContractTest {
	@TempDir Path root;

	@Test
	void acceptsBoxHeadersLeadingBoxesAndHashesUnmodifiedBytes() throws Exception {
		for (FileStorage storage : adapters()) {
			for (byte[] bytes : List.of(mp4(false, false), mp4(true, false), mp4(false, true))) {
				var stored = storage.store("clip.MP4", new ByteArrayInputStream(bytes));
				assertEquals("video/mp4", stored.mimeType());
				assertEquals(bytes.length, stored.fileSize());
				assertTrue(stored.storageKey().endsWith(".mp4"));
				assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), stored.sha256());
				assertEquals(stored.sha256(), storage.calculateSha256(stored.storageKey()));
				try (var input = storage.load(stored.storageKey()).getInputStream()) {
					assertArrayEquals(bytes, input.readAllBytes());
				}
				storage.delete(stored.storageKey());
			}
		}
	}

	@Test
	void rejectsRenamedContentWrongExtensionsAndMalformedBoxesBeforeS3Upload() throws Exception {
		for (FileStorage storage : adapters()) {
			assertThrows(FileStorageValidationException.class, () -> storage.store("clip.jpg", new ByteArrayInputStream(mp4(false, false))));
			for (byte[] bytes : List.of("fake ftyp isom".getBytes(), new byte[]{0,0,0,8,'f','t','y','p'},
					new byte[]{0,0,0,4,'f','r','e','e'}, Arrays.copyOf(mp4(true, false), 12),
					new byte[]{0,0,0,24,'f','t','y','p','q','t',' ',' ',0,0,0,0,'q','t',' ',' '},
					new byte[]{0,0,0,0,'f','r','e','e','f','t','y','p','i','s','o','m'})) {
				assertThrows(FileStorageValidationException.class, () -> storage.store("clip.mp4", new ByteArrayInputStream(bytes)));
			}
		}
		try (var paths = Files.walk(root)) {
			assertEquals(0, paths.filter(Files::isRegularFile).count(), "No failed original/temp files remain");
		}
	}

	@Test
	void bothAdaptersKeepExactly50MiBLimitAndRejectNextByte() throws Exception {
		for (FileStorage storage : adapters()) {
			long limit = 50L * 1024 * 1024;
			var stored = storage.store("clip.mp4", sizedMp4(limit));
			assertEquals(limit, stored.fileSize());
			assertEquals(64, stored.sha256().length());
			storage.delete(stored.storageKey());
			assertThrows(FileStorageValidationException.class, () -> storage.store("clip.mp4", sizedMp4(limit + 1)));
		}
	}

	private List<FileStorage> adapters() throws Exception {
		Map<String, byte[]> saved = new HashMap<>();
		var objects = mock(S3ObjectStore.class);
		doAnswer(call -> {
			assertEquals("video/mp4", call.getArgument(2));
			saved.put(call.getArgument(0), Files.readAllBytes(call.getArgument(1, Path.class)));
			return null;
		}).when(objects).put(anyString(), any(Path.class), anyString());
		when(objects.load(anyString())).thenAnswer(call -> new ByteArrayResource(saved.get(call.getArgument(0))));
		doAnswer(call -> { saved.remove(call.getArgument(0)); return null; }).when(objects).delete(anyString());
		return List.of(new FileStorageService(root.toString()), new S3FileStorage(objects));
	}

	private InputStream sizedMp4(long size) throws Exception {
		byte[] header = mp4(false, false);
		return new SequenceInputStream(new ByteArrayInputStream(header), new InputStream() {
			long remaining = size - header.length;
			@Override public int read() { return remaining-- > 0 ? 0 : -1; }
			@Override public int read(byte[] bytes, int offset, int length) {
				if (remaining <= 0) return -1;
				int count = (int) Math.min(length, remaining);
				Arrays.fill(bytes, offset, offset + count, (byte) 0);
				remaining -= count;
				return count;
			}
		});
	}

	static byte[] mp4(boolean extended, boolean leadingFree) throws Exception {
		var bytes = new ByteArrayOutputStream();
		var out = new DataOutputStream(bytes);
		if (leadingFree) { out.writeInt(12); out.writeBytes("free"); out.writeInt(0); }
		out.writeInt(extended ? 1 : 24); out.writeBytes("ftyp");
		if (extended) out.writeLong(32);
		out.writeBytes("isom"); out.writeInt(0); out.writeBytes("mp42"); out.writeBytes("avc1");
		return bytes.toByteArray();
	}
}
