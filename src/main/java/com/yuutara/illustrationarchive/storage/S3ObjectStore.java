package com.yuutara.illustrationarchive.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** The bucket and relative object key are the only S3-specific address details. */
@Component
@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
class S3ObjectStore {
	private final S3Client client;
	private final String bucket;

	S3ObjectStore(S3Client client, @Value("${storage.s3.bucket}") String bucket) {
		if (bucket.isBlank()) throw new IllegalArgumentException("S3 bucket must be configured.");
		this.client = client;
		this.bucket = bucket;
	}

	void put(String key, Path file, String contentType) {
		validateKey(key);
		try {
			client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
					.contentType(contentType).build(), RequestBody.fromFile(file));
		} catch (RuntimeException exception) {
			throw new FileStorageException("Failed to store S3 object.", exception);
		}
	}

	Resource load(String key) {
		validateKey(key);
		long length;
		try {
			length = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
		} catch (RuntimeException exception) {
			throw new FileStorageException("Failed to locate S3 object.", exception);
		}
		return new AbstractResource() {
			@Override
			public String getDescription() {
				return "S3 object " + key;
			}

			@Override
			public long contentLength() {
				return length;
			}

			@Override
			public InputStream getInputStream() throws IOException {
				try {
					return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
				} catch (RuntimeException exception) {
					throw new IOException("Failed to read S3 object.", exception);
				}
			}
		};
	}

	boolean exists(String key) {
		validateKey(key);
		try {
			client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
			return true;
		} catch (S3Exception exception) {
			if (exception.statusCode() == 404) return false;
			throw new FileStorageException("Failed to check S3 object.", exception);
		} catch (RuntimeException exception) {
			throw new FileStorageException("Failed to check S3 object.", exception);
		}
	}

	void delete(String key) {
		validateKey(key);
		try {
			client.deleteObject(request -> request.bucket(bucket).key(key));
		} catch (RuntimeException exception) {
			throw new FileStorageException("Failed to delete S3 object.", exception);
		}
	}

	static void validateKey(String key) {
		if (key == null || key.isBlank() || key.startsWith("/") || key.contains("\\")
				|| key.indexOf('\0') >= 0 || key.matches("^[A-Za-z]:.*")) {
			throw new FileStorageValidationException("Storage key must be a relative object key.");
		}
		for (String segment : key.split("/", -1)) {
			if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
				throw new FileStorageValidationException("Storage key must not contain empty or parent path segments.");
			}
		}
	}
}
