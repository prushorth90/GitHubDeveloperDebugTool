package com.githubci.intelligence.services;

import java.net.URI;

import com.githubci.intelligence.exceptions.LogStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class LogStorageServiceIT {

    @Container
    private static final GenericContainer<?> S3 =
            new GenericContainer<>(DockerImageName.parse("localstack/localstack:4.4.0"))
                    .withEnv("SERVICES", "s3").withExposedPorts(4566);

    @Test
    void storesRetrievesOverwritesAndDeletesLogsAgainstLocalStack() {
        try (var client = S3Client.builder()
                .endpointOverride(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(4566)))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build()) {
            client.createBucket(request -> request.bucket("integration-logs"));
            var storage = new LogStorageService(client, "integration-logs", new LogRedactionService("", ""));
            String key = storage.upload(1, 42, 1, 7, "password=secret\n[ERROR] failed");
            assertEquals("password=[REDACTED]\n[ERROR] failed", storage.retrieve(key));
            assertEquals(key, storage.upload(1, 42, 1, 7, "replacement"));
            assertEquals("replacement", storage.retrieve(key));
            storage.delete(key);
            storage.delete(key);
            assertEquals(HttpStatus.GONE,
                    assertThrows(LogStorageException.class, () -> storage.retrieve(key)).getStatus());
        }
    }
}
