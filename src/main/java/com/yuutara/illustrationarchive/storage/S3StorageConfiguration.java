package com.yuutara.illustrationarchive.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class S3StorageConfiguration {

	@Bean(destroyMethod = "close")
	S3Client s3Client(
			@Value("${storage.s3.endpoint:}") String endpoint,
			@Value("${storage.s3.region}") String region,
			@Value("${storage.s3.access-key}") String accessKey,
			@Value("${storage.s3.secret-key}") String secretKey,
			@Value("${storage.s3.path-style:false}") boolean pathStyle) {
		if (region.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
			throw new IllegalArgumentException("S3 region and credentials must be configured.");
		}
		var builder = S3Client.builder()
				.region(Region.of(region))
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
		if (!endpoint.isBlank()) {
			builder.endpointOverride(URI.create(endpoint));
		}
		return builder.build();
	}
}
