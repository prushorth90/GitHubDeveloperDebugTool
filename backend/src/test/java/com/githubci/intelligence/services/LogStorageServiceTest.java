package com.githubci.intelligence.services;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import com.githubci.intelligence.exceptions.LogStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LogStorageServiceTest {

    private static final String KEY = "logs/repositories/123/runs/42/attempts/2/jobs/7.log.gz";
    private final S3Client client = mock(S3Client.class);
    private final LogStorageService storage = new LogStorageService(
            client, "test-logs", new LogRedactionService("", ""));

    @Test
    void namesObjectsPredictablyAndSeparatesRepositoriesRunsAttemptsAndJobs() {
        assertEquals(KEY, LogStorageService.objectKey(123, 42, 2, 7));
        assertNotEquals(KEY, LogStorageService.objectKey(124, 42, 2, 7));
        assertNotEquals(KEY, LogStorageService.objectKey(123, 43, 2, 7));
        assertNotEquals(KEY, LogStorageService.objectKey(123, 42, 3, 7));
        assertNotEquals(KEY, LogStorageService.objectKey(123, 42, 2, 8));
        assertThrows(IllegalArgumentException.class, () -> LogStorageService.objectKey(0, 42, 2, 7));
        assertThrows(IllegalArgumentException.class, () -> LogStorageService.objectKey(123, -1, 2, 7));
        assertThrows(IllegalArgumentException.class, () -> LogStorageService.objectKey(123, 42, 0, 7));
        assertThrows(IllegalArgumentException.class, () -> LogStorageService.objectKey(123, 42, 2, -1));
    }

    @Test
    void uploadsOnlyRedactedGzipWithEncryptionAndRetrievesUtf8() throws Exception {
        assertEquals(KEY, storage.upload(123, 42, 2, 7, "password=secret\n[ERROR] failed \u00e9"));
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertEquals("test-logs", request.getValue().bucket());
        assertEquals(KEY, request.getValue().key());
        assertEquals("gzip", request.getValue().contentEncoding());
        assertEquals("text/plain; charset=utf-8", request.getValue().contentType());
        assertEquals(ServerSideEncryption.AES256, request.getValue().serverSideEncryption());
        byte[] bytes;
        try (var stream = body.getValue().contentStreamProvider().newStream()) {
            bytes = stream.readAllBytes();
        }
        String expected = "password=[REDACTED]\n[ERROR] failed \u00e9";
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals(expected, new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
        }
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes));
        assertEquals(expected, storage.retrieve(KEY));
        verify(client).getObjectAsBytes(GetObjectRequest.builder().bucket("test-logs").key(KEY).build());
    }

    @Test
    void deletesOnlyTheRequestedObject() {
        storage.delete(KEY);
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("test-logs").key(KEY).build());
    }

    @Test
    void surfacesUploadDownloadAndDeleteFailuresWithoutLeakingSdkMessages() {
        var sdkFailure = SdkClientException.create("Authorization: secret; signed-url");
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(sdkFailure);
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(sdkFailure);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(sdkFailure);
        var upload = assertThrows(LogStorageException.class, () -> storage.upload(123, 42, 2, 7, "logs"));
        var download = assertThrows(LogStorageException.class, () -> storage.retrieve(KEY));
        var delete = assertThrows(LogStorageException.class, () -> storage.delete(KEY));
        assertEquals("Could not upload job logs", upload.getMessage());
        assertEquals("Could not retrieve job logs", download.getMessage());
        assertEquals("Could not delete job logs", delete.getMessage());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, upload.getStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, download.getStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, delete.getStatus());
        assertNull(upload.getCause());
    }

    @Test
    void distinguishesExpiredLogsFromPermissionFailuresAndCorruptObjects() {
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build())
                .thenThrow(NoSuchBucketException.builder().statusCode(404).build())
                .thenThrow(S3Exception.builder().statusCode(403).build())
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] {1, 2}));
        assertEquals(HttpStatus.GONE, assertThrows(LogStorageException.class, () -> storage.retrieve(KEY)).getStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(LogStorageException.class, () -> storage.retrieve(KEY)).getStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(LogStorageException.class, () -> storage.retrieve(KEY)).getStatus());
        assertEquals(HttpStatus.BAD_GATEWAY,
                assertThrows(LogStorageException.class, () -> storage.retrieve(KEY)).getStatus());
    }

    @Test
    void rejectsKeysOutsideTheManagedNamespaceBeforeCallingS3() {
        assertThrows(IllegalArgumentException.class, () -> storage.retrieve("../secret"));
        assertThrows(IllegalArgumentException.class, () -> storage.delete("other/object"));
        assertThrows(IllegalArgumentException.class, () -> storage.retrieve(null));
        verifyNoInteractions(client);
    }
}
