package com.yuutara.illustrationarchive.storage;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3StorageSelectionTest {
	@Test
	void s3ModeSelectsBothS3Adapters() {
		try (var context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("s3Test", Map.of(
					"storage.type", "s3",
					"storage.s3.endpoint", "http://localhost:4566",
					"storage.s3.region", "us-east-1",
					"storage.s3.access-key", "test",
					"storage.s3.secret-key", "test",
					"storage.s3.bucket", "test-bucket",
					"storage.s3.path-style", "true")));
			context.register(S3StorageConfiguration.class, S3ObjectStore.class, S3FileStorage.class,
					S3ThumbnailStorage.class, FileStorageService.class, ThumbnailService.class);
			context.refresh();
			assertInstanceOf(S3FileStorage.class, context.getBean(FileStorage.class));
			assertInstanceOf(S3ThumbnailStorage.class, context.getBean(ThumbnailStorage.class));
			assertEquals(0, context.getBeansOfType(FileStorageService.class).size());
			assertEquals(0, context.getBeansOfType(ThumbnailService.class).size());
		}
	}

	@Test
	void rejectsNonRelativeObjectKeys() {
		for (String key : new String[]{"", "/absolute.png", "../escape.png", "2026-09/../escape.png",
				"2026-09//empty.png", "C:\\absolute.png", "2026-09\\file.png"}) {
			assertThrows(FileStorageValidationException.class, () -> S3ObjectStore.validateKey(key), key);
		}
		S3ObjectStore.validateKey("2026-09/image.png");
		S3ObjectStore.validateKey("thumbnails/2026-09/image.png");
	}
}
