package com.yuutara.illustrationarchive.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit opt-in test against a running LocalStack bucket, with no AWS SDK mocks. */
class S3StorageLocalStackTest {
	@Test
	@EnabledIfEnvironmentVariable(named = "S3_TEST_ENDPOINT", matches = ".+")
	void storesReadsHashesThumbnailsAndDeletesRealS3Objects() throws Exception {
		try (S3Client client = S3Client.builder()
				.endpointOverride(URI.create(System.getenv("S3_TEST_ENDPOINT")))
				.region(Region.US_EAST_1)
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
				.build()) {
			S3ObjectStore objects = new S3ObjectStore(client, "illustration-archive");
			S3FileStorage originals = new S3FileStorage(objects);
			S3ThumbnailStorage thumbnails = new S3ThumbnailStorage(objects);
			BufferedImage image = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_ARGB);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			assertTrue(ImageIO.write(image, "png", output));
			byte[] bytes = output.toByteArray();
			StoredFile stored = originals.store("example.png", new ByteArrayInputStream(bytes));
			try {
				assertTrue(stored.storageKey().matches("\\d{4}-\\d{2}/[0-9a-f]{32}\\.png"));
				var resource = originals.load(stored.storageKey());
				assertEquals(bytes.length, resource.contentLength());
				try (var input = resource.getInputStream()) {
					assertArrayEquals(bytes, input.readAllBytes());
				}
				try (var input = resource.getInputStream()) {
					assertArrayEquals(bytes, input.readAllBytes());
				}
				assertEquals(stored.sha256(), originals.calculateSha256(stored.storageKey()));
				String thumbnailKey = thumbnails.generateThumbnail(stored.storageKey());
				assertEquals("thumbnails/" + stored.storageKey(), thumbnailKey);
				assertEquals(thumbnailKey, thumbnails.generateThumbnail(stored.storageKey()));
				try (var input = thumbnails.loadThumbnail(stored.storageKey()).getInputStream()) {
					BufferedImage thumbnail = ImageIO.read(input);
					assertEquals(600, thumbnail.getWidth());
					assertEquals(300, thumbnail.getHeight());
				}
			} finally {
				thumbnails.deleteThumbnail(stored.storageKey());
				originals.delete(stored.storageKey());
			}
			assertFalse(objects.exists(stored.storageKey()));
			assertFalse(objects.exists("thumbnails/" + stored.storageKey()));
		}
	}
}
