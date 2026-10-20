package com.githubci.intelligence.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.githubci.intelligence.exceptions.LogStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

@Service
public class LogStorageService {

    private static final Pattern KEY = Pattern.compile(
            "logs/repositories/[1-9][0-9]*/runs/[1-9][0-9]*/attempts/[1-9][0-9]*/jobs/[1-9][0-9]*\\.log\\.gz");
    private final S3Client client;
    private final String bucket;
    private final LogRedactionService redactor;

    public LogStorageService(S3Client client, @Value("${app.s3.bucket-name}") String bucket,
            LogRedactionService redactor) {
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("S3 log bucket is required");
        }
        this.client = client;
        this.bucket = bucket;
        this.redactor = redactor;
    }

    public static String objectKey(long repositoryId, long runId, int attempt, long jobId) {
        if (repositoryId <= 0 || runId <= 0 || attempt <= 0 || jobId <= 0) {
            throw new IllegalArgumentException("Log identifiers and run attempt must be positive");
        }
        return "logs/repositories/" + repositoryId + "/runs/" + runId
                + "/attempts/" + attempt + "/jobs/" + jobId + ".log.gz";
    }

    public String upload(long repositoryId, long runId, int attempt, long jobId, String logs) {
        String key = objectKey(repositoryId, runId, attempt, jobId);
        byte[] compressed;
        try (var bytes = new ByteArrayOutputStream()) {
            try (var gzip = new GZIPOutputStream(bytes)) {
                gzip.write(redactor.redact(logs).getBytes(StandardCharsets.UTF_8));
            }
            compressed = bytes.toByteArray();
        } catch (IOException failure) {
            throw new LogStorageException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not compress job logs");
        }
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                            .contentType("text/plain; charset=utf-8").contentEncoding("gzip")
                            .serverSideEncryption(ServerSideEncryption.AES256).build(),
                    RequestBody.fromBytes(compressed));
            return key;
        } catch (SdkException failure) {
            // SDK messages can contain signed URLs or headers; do not propagate them.
            throw unavailable("upload");
        }
    }

    public String retrieve(String key) {
        validateKey(key);
        byte[] compressed;
        try {
            compressed = client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray();
        } catch (S3Exception failure) {
            if (failure instanceof NoSuchKeyException
                    || (failure.statusCode() == 404 && failure.awsErrorDetails() != null
                    && "NoSuchKey".equals(failure.awsErrorDetails().errorCode()))) {
                throw new LogStorageException(HttpStatus.GONE, "Job logs are unavailable or expired");
            }
            throw unavailable("retrieve");
        } catch (SdkException failure) {
            throw unavailable("retrieve");
        }
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return redactor.redact(new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new LogStorageException(HttpStatus.BAD_GATEWAY, "Stored job logs are not valid gzip data");
        }
    }

    public void delete(String key) {
        validateKey(key);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException failure) {
            throw unavailable("delete");
        }
    }

    private static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid job log object key");
        }
    }

    private static LogStorageException unavailable(String operation) {
        return new LogStorageException(HttpStatus.SERVICE_UNAVAILABLE, "Could not " + operation + " job logs");
    }
}
