package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class XPhotoDownloadServiceTest {
	@TempDir
	Path storageRoot;

	private HttpClient httpClient;
	private XPhotoDownloadService service;
	private final List<HttpRequest> requests = new ArrayList<>();

	@BeforeEach
	void setUp() {
		httpClient = mock(HttpClient.class);
		service = new XPhotoDownloadService(httpClient, new FileStorageService(storageRoot.toString()));
	}

	@Test
	void buildsHighQualityUrlAndReturnsStoredFileWhenDimensionsMatch() throws Exception {
		byte[] image = image("jpg", 13, 9);
		respond(200, new ByteArrayInputStream(image));

		StoredFile stored = service.download(media("https://pbs.twimg.com/media/Example123.jpg", 13, 9));

		assertEquals("https://pbs.twimg.com/media/Example123?format=jpg&name=4096x4096",
				requests.get(0).uri().toString());
		assertEquals("Example123.jpg", stored.originalFilename());
		assertEquals("image/jpeg", stored.mimeType());
		assertEquals(image.length, stored.fileSize());
		assertEquals(64, stored.sha256().length());
		assertArrayEquals(image, Files.readAllBytes(storageRoot.resolve(stored.storageKey())));
	}

	@Test
	void handlesJpgJpegAndPngExtensions() throws Exception {
		for (String extension : List.of("jpg", "jpeg", "png")) {
			String imageFormat = extension.equals("png") ? "png" : "jpg";
			respond(200, new ByteArrayInputStream(image(imageFormat, 4, 3)));
			StoredFile stored = service.download(media("https://pbs.twimg.com/media/Photo." + extension, 4, 3));
			assertEquals("image/" + (imageFormat.equals("jpg") ? "jpeg" : "png"), stored.mimeType());
			assertEquals("https://pbs.twimg.com/media/Photo?format=" + imageFormat + "&name=4096x4096",
				requests.get(requests.size() - 1).uri().toString());
			assertTrue(Files.isRegularFile(storageRoot.resolve(stored.storageKey())));
		}
	}

	@Test
	void widthMismatchFailsAndDeletesStoredFile() throws Exception {
		respond(200, new ByteArrayInputStream(image("png", 10, 8)));

		FullResolutionMismatchException failure = assertThrows(FullResolutionMismatchException.class,
				() -> service.download(media("https://pbs.twimg.com/media/Photo.png", 11, 8)));

		assertTrue(failure.getMessage().contains("expected 11x8, got 10x8"));
		assertNoStoredFiles();
	}

	@Test
	void heightMismatchFailsAndDeletesStoredFile() throws Exception {
		respond(200, new ByteArrayInputStream(image("jpg", 10, 8)));

		assertThrows(FullResolutionMismatchException.class,
				() -> service.download(media("https://pbs.twimg.com/media/Photo.jpg", 10, 9)));
		assertNoStoredFiles();
	}

	@Test
	void nonSuccessfulHttpResponseFailsWithoutStoring() throws Exception {
		respond(404, new ByteArrayInputStream(new byte[0]));

		XPhotoDownloadException failure = assertThrows(XPhotoDownloadException.class,
				() -> service.download(media("https://pbs.twimg.com/media/Photo.jpg", 10, 8)));
		assertEquals(404, failure.upstreamStatus());
		assertNoStoredFiles();
	}

	@Test
	void requestFailureFailsWithoutStoring() throws Exception {
		doThrow(new IOException("network unavailable")).when(httpClient)
				.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		assertThrows(XPhotoDownloadException.class,
				() -> service.download(media("https://pbs.twimg.com/media/Photo.jpg", 10, 8)));
		assertNoStoredFiles();
	}

	@Test
	void responseCloseFailureAfterStorageDeletesStoredFile() throws Exception {
		byte[] image = image("png", 3, 2);
		respond(200, new ByteArrayInputStream(image) {
			@Override
			public void close() throws IOException {
				throw new IOException("close failed");
			}
		});

		assertThrows(XPhotoDownloadException.class,
				() -> service.download(media("https://pbs.twimg.com/media/Photo.png", 3, 2)));
		assertNoStoredFiles();
	}

	@Test
	void invalidMagicNumberFailsWithoutStoring() throws Exception {
		respond(200, new ByteArrayInputStream("not an image".getBytes()));

		assertThrows(FileStorageValidationException.class,
				() -> service.download(media("https://pbs.twimg.com/media/Photo.jpg", 10, 8)));
		assertNoStoredFiles();
	}

	@Test
	void imageWithJpegSignatureButUnreadableMetadataIsDeleted() throws Exception {
		respond(200, new ByteArrayInputStream(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 1}));

		assertThrows(XPhotoDownloadException.class,
				() -> service.download(media("https://pbs.twimg.com/media/Photo.jpg", 10, 8)));
		assertNoStoredFiles();
	}

	@Test
	void existingStorageLimitRejectsMoreThan50MbActuallyRead() throws Exception {
		byte[] prefix = image("jpg", 1, 1);
		InputStream oversized = new InputStream() {
			private long remaining = 50L * 1024 * 1024 + 1;
			private int prefixOffset;

			@Override
			public int read() {
				if (remaining == 0) return -1;
				remaining--;
				return prefixOffset < prefix.length ? prefix[prefixOffset++] & 0xff : 0;
			}

			@Override
			public int read(byte[] buffer, int offset, int length) {
				if (remaining == 0) return -1;
				int count = (int) Math.min(remaining, length);
				for (int i = 0; i < count; i++) buffer[offset + i] =
						prefixOffset < prefix.length ? prefix[prefixOffset++] : 0;
				remaining -= count;
				return count;
			}
		};
		respond(200, oversized);

		assertThrows(FileStorageValidationException.class,
				() -> service.download(media("https://pbs.twimg.com/media/Photo.jpg", 1, 1)));
		assertNoStoredFiles();
	}

	@Test
	void rejectsNonPhotoAndMissingDimensionsBeforeHttp() {
		assertThrows(XPhotoDownloadException.class, () -> service.download(new XLikeMedia(
				"key", 0, "video", "https://pbs.twimg.com/media/Photo.jpg", 1, 1)));
		assertThrows(XPhotoDownloadException.class, () -> service.download(new XLikeMedia(
				"key", 0, "photo", "https://pbs.twimg.com/media/Photo.jpg", null, 1)));
		verifyNoInteractions(httpClient);
	}

	@Test
	void rejectsUnexpectedHostAndUnsupportedExtensionBeforeHttp() {
		assertThrows(XPhotoDownloadException.class,
				() -> service.download(media("https://elsewhere.example/media/Photo.jpg", 1, 1)));
		assertThrows(XPhotoDownloadException.class,
				() -> service.download(media("https://pbs.twimg.com/media/Photo.gif", 1, 1)));
		verifyNoInteractions(httpClient);
	}

	private XLikeMedia media(String url, Integer width, Integer height) {
		return new XLikeMedia("media-key", 0, "photo", url, width, height);
	}

	@SuppressWarnings("unchecked")
	private void respond(int status, InputStream body) throws Exception {
		HttpResponse<InputStream> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);
		doAnswer(invocation -> {
			requests.add(invocation.getArgument(0));
			return response;
		}).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	private byte[] image(String format, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, format, output));
		return output.toByteArray();
	}

	private void assertNoStoredFiles() throws IOException {
		try (var files = Files.walk(storageRoot)) {
			assertFalse(files.anyMatch(Files::isRegularFile));
		}
	}
}
