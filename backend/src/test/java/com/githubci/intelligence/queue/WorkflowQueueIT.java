package com.githubci.intelligence.queue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubJobs;
import com.githubci.intelligence.dto.GitHubRepository;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.exceptions.AnalysisConflictException;
import com.githubci.intelligence.exceptions.LogStorageException;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import com.githubci.intelligence.repositories.WorkflowEventRepository;
import com.githubci.intelligence.services.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
class WorkflowQueueIT {

    @Container
    private static final GenericContainer<?> AWS =
            new GenericContainer<>(DockerImageName.parse("localstack/localstack:4.4.0"))
                    .withEnv("SERVICES", "sqs,s3,dynamodb")
                    .withEnv("SQS_ENDPOINT_STRATEGY", "path").withExposedPorts(4566);
    private static SqsClient sqs;
    private static S3Client s3;
    private static DynamoDbClient dynamo;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Instant now = Instant.parse("2026-10-20T19:00:00Z");
    private final WorkflowEvent event = new WorkflowEvent(1, "octo/demo", null, 42, 2,
            "failure", UUID.randomUUID().toString());
    private String table;
    private String queue;
    private String dlq;
    private String bucket;
    private WorkflowEventRepository events;
    private WorkflowAnalysisRepository analyses;
    private GitHubService github;
    private LogStorageService logs;
    private WorkflowEventWorker worker;

    @BeforeAll
    static void connect() {
        URI endpoint = URI.create("http://" + AWS.getHost() + ":" + AWS.getMappedPort(4566));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));
        sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        s3 = S3Client.builder().endpointOverride(endpoint).forcePathStyle(true).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        dynamo = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
    }

    @AfterAll
    static void disconnect() {
        if (sqs != null) sqs.close();
        if (s3 != null) s3.close();
        if (dynamo != null) dynamo.close();
    }

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        table = "analyses-" + suffix;
        bucket = "logs-" + suffix;
        dynamo.createTable(request -> request.tableName(table)
                .attributeDefinitions(attribute("pk"), attribute("sk"))
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .billingMode("PAY_PER_REQUEST"));
        dynamo.waiter().waitUntilTableExists(request -> request.tableName(table));
        s3.createBucket(request -> request.bucket(bucket));
        dlq = sqs.createQueue(request -> request.queueName("dlq-" + suffix)).queueUrl();
        String arn = sqs.getQueueAttributes(request -> request.queueUrl(dlq)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        queue = sqs.createQueue(request -> request.queueName("events-" + suffix)
                .attributes(java.util.Map.of(QueueAttributeName.REDRIVE_POLICY,
                        "{\"deadLetterTargetArn\":\"" + arn + "\",\"maxReceiveCount\":\"2\"}"))).queueUrl();
        events = spy(eventsAt(now));
        analyses = spy(new WorkflowAnalysisRepository(
                DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build(), mapper,
                Clock.fixed(now, ZoneOffset.UTC), table, Duration.ofMinutes(5)));
        github = mock(GitHubService.class);
        var redactor = new LogRedactionService("", "");
        logs = spy(new LogStorageService(s3, bucket, redactor));
        worker = new WorkflowEventWorker(events, new WorkflowRunAnalysisService(
                github, new FailureAnalyzerService(), analyses, logs, redactor));
        var run = new GitHubWorkflowRun(42, "build", 1, 1, 2, "push", "completed", "failure",
                "main", "sha", "https://github.com/octo/demo/actions/runs/42", now, now,
                new GitHubRepository(1, "demo", "octo/demo", "https://github.com/octo/demo", false));
        when(github.getWorkflowRunAttempt("octo", "demo", 42, 2)).thenReturn(run);
        when(github.listJobsForAttempt("octo", "demo", 42, 2, 1, 100))
                .thenReturn(new GitHubJobs(1, List.of(new GitHubJob(
                        7, 42, "build", "completed", "failure", null, null, null, List.of()))));
        when(github.getFailedJobLogs("octo", "demo", 7))
                .thenReturn("Authorization: Bearer secret-token\nfatal: build failed");
    }

    @Test
    void queueToSanitizedS3AndDynamoThenDuplicateIsNoOp() {
        var publisher = new SqsWorkflowEventPublisher(sqs, mapper, queue);
        var consumer = new SqsWorkflowEventConsumer(sqs, mapper, worker, queue, 120);
        publisher.publish(event);
        consumer.consume(receive(queue));
        assertTrue(receiveMessages(queue).isEmpty());
        var stored = analyses.findRun("octo/demo", 42, 2).orElseThrow();
        assertEquals(AnalysisStatus.COMPLETED, stored.status());
        String key = stored.analysis().failedJobs().getFirst().logObjectKey();
        assertFalse(logs.retrieve(key).contains("secret-token"));
        assertFalse(mapper.writeValueAsString(stored.analysis()).contains("secret-token"));
        assertTrue(events.claim(event).completed());
        publisher.publish(event);
        consumer.consume(receive(queue));
        verify(github, times(1)).getFailedJobLogs("octo", "demo", 7);
        assertEquals(1, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
    }

    @Test
    void s3SuccessThenDynamoFailureRetriesWithoutExtraObjects() {
        doThrow(new IllegalStateException("Dynamo unavailable")).doCallRealMethod()
                .when(analyses).complete(any(), any());
        assertThrows(IllegalStateException.class, () -> worker.process(event));
        assertEquals(AnalysisStatus.FAILED, analyses.findRun("octo/demo", 42, 2).orElseThrow().status());
        assertEquals(1, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
        worker.process(event);
        assertTrue(events.claim(event).completed());
        assertEquals(1, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
        verify(logs, times(2)).upload(eq(1L), eq(42L), eq(2), eq(7L), anyString());
    }

    @Test
    void committedAnalysisWithAmbiguousResponseRecoversWithoutDownloadingAgain() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Completion response lost");
        }).when(analyses).complete(any(), any());
        assertThrows(IllegalStateException.class, () -> worker.process(event));
        assertEquals(AnalysisStatus.COMPLETED, analyses.findRun("octo/demo", 42, 2).orElseThrow().status());
        worker.process(event);
        verify(github, times(1)).getFailedJobLogs("octo", "demo", 7);
        assertTrue(events.claim(event).completed());
    }

    @Test
    void failedUploadCannotCompleteOrAcknowledge() {
        doThrow(new LogStorageException(HttpStatus.SERVICE_UNAVAILABLE, "S3 unavailable"))
                .doCallRealMethod().when(logs).upload(anyLong(), anyLong(), anyInt(), anyLong(), anyString());
        assertThrows(LogStorageException.class, () -> worker.process(event));
        assertEquals(AnalysisStatus.FAILED, analyses.findRun("octo/demo", 42, 2).orElseThrow().status());
        verify(events, never()).complete(any());
        assertEquals(0, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
        worker.process(event);
        assertTrue(events.claim(event).completed());
    }

    @Test
    void committedEventWithLostResponseDoesNotRunWorkAgain() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Event completion response lost");
        }).when(events).complete(any());
        assertThrows(IllegalStateException.class, () -> worker.process(event));
        assertTrue(events.claim(event).completed());
        worker.process(event);
        verify(github, times(1)).getWorkflowRunAttempt("octo", "demo", 42, 2);
    }

    @Test
    void mismatchedRepositoryOrAttemptCannotPersistAnAnalysis() {
        var wrongRepository = new WorkflowEvent(2, "octo/demo", null, 42, 2,
                "failure", UUID.randomUUID().toString());
        assertThrows(com.githubci.intelligence.exceptions.GitHubException.class,
                () -> worker.process(wrongRepository));
        when(github.getWorkflowRunAttempt("octo", "demo", 42, 2)).thenReturn(
                new GitHubWorkflowRun(42, "build", 1, 1, 3, "push", "completed", "failure",
                        "main", "sha", null, now, now,
                        new GitHubRepository(1, "demo", "octo/demo", null, false)));
        assertThrows(com.githubci.intelligence.exceptions.GitHubException.class, () -> worker.process(event));
        verifyNoInteractions(analyses, logs);
        verify(events, never()).complete(any());
    }

    @Test
    void ledgerFencesStaleWorkersAndRecoversCrashesAndRenames() {
        var first = events.claim(event);
        assertThrows(AnalysisConflictException.class, () -> events.claim(event));
        eventsAt(now.plusSeconds(200)).renew(first);
        assertThrows(AnalysisConflictException.class, () -> eventsAt(now.plusSeconds(301)).claim(event));
        var takeover = eventsAt(now.plusSeconds(501)).claim(event);
        assertThrows(AnalysisConflictException.class, () -> eventsAt(now.plusSeconds(502)).complete(first));
        assertThrows(AnalysisConflictException.class, () -> eventsAt(now.plusSeconds(502)).fail(first));
        eventsAt(now.plusSeconds(502)).complete(takeover);
        var renamed = new WorkflowEvent(1, "other/renamed", null, 42, 2,
                "failure", UUID.randomUUID().toString());
        assertTrue(events.claim(renamed).completed());
        assertFalse(events.claim(new WorkflowEvent(1, "other/renamed", null, 42, 3,
                "failure", UUID.randomUUID().toString())).completed());
    }

    @Test
    void poisonMessageMovesToDlqWithoutWorkerAcknowledgement() {
        sqs.sendMessage(request -> request.queueUrl(queue).messageBody("invalid-json"));
        var consumer = new SqsWorkflowEventConsumer(sqs, mapper, worker, queue, 120);
        for (int attempt = 0; attempt < 2; attempt++) {
            Message message = receive(queue);
            consumer.consume(message);
            sqs.changeMessageVisibility(request -> request.queueUrl(queue)
                    .receiptHandle(message.receiptHandle()).visibilityTimeout(0));
        }
        assertTrue(receiveMessages(queue).isEmpty());
        assertEquals("invalid-json", receive(dlq).body());
        verifyNoInteractions(github);
    }

    private WorkflowEventRepository eventsAt(Instant instant) {
        return new WorkflowEventRepository(dynamo, table, Clock.fixed(instant, ZoneOffset.UTC), Duration.ofMinutes(5));
    }

    private static List<Message> receiveMessages(String url) {
        return sqs.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(1).visibilityTimeout(120)
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).messages();
    }

    private static Message receive(String url) {
        var messages = receiveMessages(url);
        assertEquals(1, messages.size());
        return messages.getFirst();
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }
}
