package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AiAnalysisResponse;
import com.yuutara.illustrationarchive.dto.AiAnalysisResponse.Media;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.AssetRepository.AnalysisSource;
import com.yuutara.illustrationarchive.repository.IllustrationDetailBase;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import com.yuutara.illustrationarchive.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IllustrationAiAnalysisServiceTest {
	private final IllustrationRepository illustrations = mock(IllustrationRepository.class);
	private final AssetRepository assets = mock(AssetRepository.class);
	private final FileStorage storage = mock(FileStorage.class);
	private final GeminiClient client = mock(GeminiClient.class);
	private final IllustrationAiAnalysisService service = new IllustrationAiAnalysisService(illustrations, assets, storage, client);

	private void existing() {
		when(illustrations.findDetailBaseById(7)).thenReturn(Optional.of(
				new IllustrationDetailBase(7L, null, null, null, null, null, null)));
	}

	private AnalysisSource source(long id, String mime) throws Exception {
		byte[] bytes = image(80, 40, mime.equals("image/jpeg") ? "jpeg" : "png", false);
		String key = id + ".img";
		when(storage.load(key)).thenReturn(new ByteArrayResource(bytes));
		return new AnalysisSource(id, key, mime, bytes.length, (int) id);
	}

	@Test
	void sendsOneAndFourStaticImagesWithoutWritingArchive() throws Exception {
		existing();
		for (int count : List.of(1, 4)) {
			var sources = new java.util.ArrayList<AnalysisSource>();
			for (int i = 0; i < count; i++) sources.add(source(i + 1, i % 2 == 0 ? "image/jpeg" : "image/png"));
			when(assets.findAnalysisSources(7)).thenReturn(sources);
			doAnswer(call -> {
				List<String> images = call.getArgument(0);
				List<Media> media = call.getArgument(1);
				assertEquals(count, images.size());
				assertEquals(IntStream.rangeClosed(1, count).boxed().toList(), media.stream().map(Media::position).toList());
				for (String image : images) assertTrue(image.startsWith("data:image/jpeg;base64,/9j/"));
				return new AiAnalysisResponse("摘要", List.of(), List.of(), List.of(), List.of(), "free", media, List.of());
			}).when(client).analyze(anyList(), anyList(), anyList());
			assertEquals(count, service.analyze(7).analyzedAssets().size());
		}
		verify(storage, never()).store(anyString(), any());
		verify(storage, never()).delete(anyString());
		verify(assets, never()).insert(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyInt());
	}

	@Test
	void keepsRepositoryOrderAndMapsSkippedGifAndMp4() throws Exception {
		existing();
		var gif = new AnalysisSource(31, "never.gif", "image/gif", 10, 0);
		var mp4 = new AnalysisSource(33, "never.mp4", "video/mp4", 10, 2);
		var firstStatic = source(32, "image/png");
		var secondStatic = source(34, "image/jpeg");
		when(assets.findAnalysisSources(7)).thenReturn(List.of(gif, firstStatic, mp4, secondStatic));
		service.analyze(7);
		verify(client).analyze(anyList(), eq(List.of(new Media(32, 2, "image/png"), new Media(34, 4, "image/jpeg"))),
				eq(List.of(new Media(31, 1, "image/gif"), new Media(33, 3, "video/mp4"))));
		verify(storage, never()).load("never.gif");
		verify(storage, never()).load("never.mp4");
	}

	@Test
	void missingIllustrationIs404BeforeAnyExternalWork() {
		status(404, () -> service.analyze(7));
		verifyNoInteractions(client, assets, storage);
	}

	@Test
	void rejectsEmptyAllDynamicAndMoreThanFourBeforeLoadingAnyImage() {
		existing();
		var dynamic = new AnalysisSource(1, "a", "video/mp4", 10, 0);
		for (var sources : List.of(List.<AnalysisSource>of(), List.of(dynamic),
				IntStream.range(0, 5).mapToObj(i -> new AnalysisSource(i, "a", "image/jpeg", 10, i)).toList())) {
			when(assets.findAnalysisSources(7)).thenReturn(sources);
			status(422, () -> service.analyze(7));
		}
		verifyNoInteractions(storage);
		verify(client, never()).analyze(anyList(), anyList(), anyList());
	}

	@Test
	void resizesTo2048PreservesAspectAndDoesNotEnlargeSmallImages() throws Exception {
		for (int width : List.of(320, 4096)) {
			byte[] bytes = image(width, width / 2, "jpeg", false);
			when(storage.load("image")).thenReturn(new ByteArrayResource(bytes));
			byte[] jpeg = service.prepareImage(new AnalysisSource(1, "image", "image/jpeg", bytes.length, 0));
			var output = ImageIO.read(new ByteArrayInputStream(jpeg));
			assertEquals(Math.min(2048, width), output.getWidth());
			assertEquals(Math.min(2048, width) / 2, output.getHeight());
		}
	}

	@Test
	void transparentPngBecomesWhiteJpegAndClosesSource() throws Exception {
		byte[] bytes = image(80, 40, "png", true);
		var input = spy(new ByteArrayInputStream(bytes));
		var resource = mock(org.springframework.core.io.Resource.class);
		when(resource.getInputStream()).thenReturn(input);
		when(storage.load("png")).thenReturn(resource);
		byte[] jpeg = service.prepareImage(new AnalysisSource(1, "png", "image/png", bytes.length, 0));
		assertEquals(0xff, jpeg[0] & 0xff);
		var output = ImageIO.read(new ByteArrayInputStream(jpeg));
		assertEquals(Color.WHITE.getRGB(), output.getRGB(0, 0));
		assertFalse(output.getColorModel().hasAlpha());
		verify(input).close();
	}

	@Test
	void rejectsOversizedMetadataActualBytesPixelsCorruptionAndMimeMismatch() throws Exception {
		status(422, () -> service.prepareImage(new AnalysisSource(1, "a", "image/jpeg", 21L * 1024 * 1024, 0)));
		verifyNoInteractions(storage);
		when(storage.load("a")).thenReturn(new ByteArrayResource(new byte[IllustrationAiAnalysisService.MAX_SOURCE_BYTES + 1]));
		status(422, () -> service.prepareImage(new AnalysisSource(1, "a", "image/jpeg", 1, 0)));
		byte[] png = image(80, 40, "png", false);
		when(storage.load("a")).thenReturn(new ByteArrayResource(png));
		status(422, () -> service.prepareImage(new AnalysisSource(1, "a", "image/jpeg", png.length, 0)));
		ByteBuffer.wrap(png, 16, 4).putInt(1_000_000);
		status(422, () -> service.prepareImage(new AnalysisSource(1, "a", "image/png", png.length, 0)));
		when(storage.load("a")).thenReturn(new ByteArrayResource(new byte[]{1, 2, 3}));
		status(422, () -> service.prepareImage(new AnalysisSource(1, "a", "image/png", 3, 0)));
	}

	@Test
	void acceptsAValidTwelveMibOriginalAndStillResizesInMemory() throws Exception {
		byte[] paddedJpeg = java.util.Arrays.copyOf(image(80, 40, "jpeg", false), 12_691_206);
		when(storage.load("large-original")).thenReturn(new ByteArrayResource(paddedJpeg));
		byte[] prepared = service.prepareImage(new AnalysisSource(25, "large-original", "image/jpeg", paddedJpeg.length, 0));
		assertNotNull(ImageIO.read(new ByteArrayInputStream(prepared)));
		assertTrue(prepared.length < IllustrationAiAnalysisService.MAX_JPEG_BYTES);
	}

	private static byte[] image(int width, int height, String format, boolean transparent) throws Exception {
		var image = new BufferedImage(width, height, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		var output = new ByteArrayOutputStream();
		try (var stream = new MemoryCacheImageOutputStream(output)) { assertTrue(ImageIO.write(image, format, stream)); }
		return output.toByteArray();
	}

	private static void status(int expected, Runnable call) {
		assertEquals(expected, assertThrows(ResponseStatusException.class, call::run).getStatusCode().value());
	}
}
