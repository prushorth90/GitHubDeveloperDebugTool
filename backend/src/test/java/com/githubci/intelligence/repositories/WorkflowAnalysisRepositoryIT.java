package com.githubci.intelligence.repositories;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubJobs;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.dto.WorkflowRunAnalysis.FailedJobAnalysis;
import com.githubci.intelligence.dto.WorkflowRunAnalysis.LogStatus;
import com.githubci.intelligence.exceptions.AnalysisConflictException;
import com.githubci.intelligence.exceptions.GitHubException;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.services.FailureAnalyzerService;
import com.githubci.intelligence.services.GitHubService;
import com.githubci.intelligence.services.WorkflowRunAnalysisService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class WorkflowAnalysisRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-10-20T07:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String REPOSITORY = "octo/demo";
    private static final FailureAnalyzerService ANALYZER = new FailureAnalyzerService();

    @Container
    private static final GenericContainer<?> DYNAMODB =
            new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:3.1.0"))
                    .withExposedPorts(8000)
                    .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    private static DynamoDbClient client;
    private static DynamoDbEnhancedClient enhanced;
    private String tableName;
    private WorkflowAnalysisRepository repository;

    @BeforeAll
    static void connect() {
        client = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    @BeforeEach
    void createTable() {
        tableName = "workflow-analyses-test-" + UUID.randomUUID();
        client.createTable(request -> request.tableName(tableName).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attribute("pk"), attribute("sk"), attribute("failurePk"), attribute("failureSk"))
                .keySchema(key("pk", KeyType.HASH), key("sk", KeyType.RANGE))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(WorkflowAnalysisRepository.RECENT_FAILURES_INDEX)
                        .keySchema(key("failurePk", KeyType.HASH), key("failureSk", KeyType.RANGE))
                        .projection(projection -> projection.projectionType(ProjectionType.ALL)).build()));
        client.waiter().waitUntilTableExists(request -> request.tableName(tableName));
        repository = repositoryAt(NOW);
    }

    @AfterEach
    void deleteTable() {
        client.deleteTable(request -> request.tableName(tableName));
    }

    @Test
    void roundTripsMetadataFailedJobsAndEveryAnalysisField() {
        var run = run(42, 1, NOW.minusSeconds(30));
        var claim = repository.claim(REPOSITORY, run);
        var processing = repository.findRun("OCTO/Demo", 42, 1).orElseThrow();
        assertEquals(AnalysisStatus.PROCESSING, processing.status());
        assertEquals(NOW.plus(LEASE), processing.leaseUntil());
        assertNull(processing.analysis());

        var analysis = failure(42);
        repositoryAt(NOW.plusSeconds(2)).complete(claim, analysis);
        var stored = repository.findRun(REPOSITORY, 42, 1).orElseThrow();
        assertEquals(run, stored.workflowRun());
        assertEquals(analysis, stored.analysis());
        assertEquals(AnalysisStatus.COMPLETED, stored.status());
        assertEquals(NOW, stored.createdAt());
        assertEquals(NOW.plusSeconds(2), stored.updatedAt());
        assertNull(stored.leaseUntil());
        var diagnosis = stored.analysis().failedJobs().getFirst().analysis();
        assertTrue(diagnosis.fingerprint().matches("[0-9a-f]{64}"));
        assertNotNull(diagnosis.category());
        assertFalse(diagnosis.normalizedSignature().isBlank());
        assertFalse(diagnosis.suggestedInvestigation().isBlank());
        assertTrue(repository.findRun(REPOSITORY, 999, 1).isEmpty());
    }

    @Test
    void completedRetriesReturnOriginalResultWithoutOverwriting() {
        var run = run(42, 1, NOW);
        var first = repository.claim(REPOSITORY, run);
        repository.complete(first, failure(42));
        var duplicate = repositoryAt(NOW.plusSeconds(10)).claim(REPOSITORY, run);
        assertTrue(duplicate.completed());
        assertEquals(failure(42), duplicate.record().analysis());
        assertEquals(NOW, duplicate.record().updatedAt());
        assertThrows(AnalysisConflictException.class, () -> repository.complete(first, empty(42)));
        assertFalse(repository.fail(first));
        assertThrows(IllegalArgumentException.class, () -> repository.complete(duplicate, empty(42)));
    }

    @Test
    void failedProcessingCanBeRetriedWithoutLosingCreationTime() {
        var run = run(42, 1, NOW);
        var first = repository.claim(REPOSITORY, run);
        assertTrue(repository.fail(first));
        assertEquals(AnalysisStatus.FAILED, repository.findRun(REPOSITORY, 42, 1).orElseThrow().status());
        var later = repositoryAt(NOW.plusSeconds(10));
        var retry = later.claim(REPOSITORY, run);
        assertNotEquals(first.token(), retry.token());
        assertEquals(NOW, retry.record().createdAt());
        later.complete(retry, failure(42));
        assertEquals(AnalysisStatus.COMPLETED, repository.findRun(REPOSITORY, 42, 1).orElseThrow().status());
    }

    @Test
    void expiredLeasesCanBeReclaimedAndStaleWorkersAreFenced() {
        var run = run(42, 1, NOW);
        var first = repository.claim(REPOSITORY, run);
        assertThrows(AnalysisConflictException.class, () -> repository.claim(REPOSITORY, run));
        var later = repositoryAt(NOW.plus(LEASE));
        assertThrows(AnalysisConflictException.class, () -> later.complete(first, failure(42)));
        assertFalse(later.fail(first));
        var retry = later.claim(REPOSITORY, run);
        assertNotEquals(first.token(), retry.token());
        assertThrows(AnalysisConflictException.class, () -> repository.complete(first, empty(42)));
        assertFalse(repository.fail(first));
        later.complete(retry, failure(42));
        assertEquals(failure(42), later.findRun(REPOSITORY, 42, 1).orElseThrow().analysis());
    }

    @Test
    void onlyOneConcurrentWorkerCanClaimEachRun() throws Exception {
        var start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try {
                repository.claim(REPOSITORY, run(42, 1, NOW));
                return true;
            } catch (AnalysisConflictException conflict) {
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertNotEquals(first.get(), second.get());
        }
    }

    @Test
    void runAttemptsAndRepositoriesAreIndependent() {
        repository.complete(repository.claim(REPOSITORY, run(42, 1, NOW)), failure(42));
        repository.complete(repository.claim(REPOSITORY, run(42, 2, NOW)), empty(42));
        repository.claim("octo/other", run(42, 1, NOW));
        assertEquals(failure(42), repository.findRun(REPOSITORY, 42, 1).orElseThrow().analysis());
        assertEquals(empty(42), repository.findRun(REPOSITORY, 42, 2).orElseThrow().analysis());
        assertEquals(AnalysisStatus.PROCESSING, repository.findRun("octo/other", 42, 1).orElseThrow().status());
    }

    @Test
    void recentFailuresUseRunChronologyNotRunIdOrProcessingOrder() {
        repository.complete(repository.claim(REPOSITORY, run(99, 1, NOW.minusSeconds(60))), failure(99));
        repository.complete(repository.claim(REPOSITORY, run(1, 1, NOW)), failure(1));
        repository.complete(repository.claim(REPOSITORY, run(44, 1, NOW.minusSeconds(30))), failure(44));
        repository.complete(repository.claim(REPOSITORY, run(2, 1, NOW.plusSeconds(10))), empty(2));
        repository.claim(REPOSITORY, run(3, 1, NOW.plusSeconds(20)));
        var failed = repository.claim(REPOSITORY, run(4, 1, NOW.plusSeconds(30)));
        repository.fail(failed);
        var other = repository.claim("octo/other", run(5, 1, NOW.plusSeconds(40)));
        repository.complete(other, new WorkflowRunAnalysis("octo/other", 5, failure(5).failedJobs()));

        assertEquals(List.of(1L, 44L), repository.recentFailures("OCTO/DEMO", 2).stream()
                .map(item -> item.workflowRun().id()).toList());
        assertEquals(3, repository.recentFailures(REPOSITORY, 100).size());
        assertTrue(repository.recentFailures("absent/repo", 10).isEmpty());
    }

    @Test
    void unavailableLogsArePersistedAsFailuresWithoutInventingFingerprints() {
        var job = job(42);
        var result = new WorkflowRunAnalysis(REPOSITORY, 42,
                List.of(new FailedJobAnalysis(job, LogStatus.UNAVAILABLE, "Logs expired", null)));
        repository.complete(repository.claim(REPOSITORY, run(42, 1, NOW)), result);
        assertEquals(result, repository.findRun(REPOSITORY, 42, 1).orElseThrow().analysis());
        assertEquals(1, repository.recentFailures(REPOSITORY, 10).size());
    }

    @Test
    void rejectsMismatchedAndOversizedResultsWithoutPartialCompletion() {
        var claim = repository.claim(REPOSITORY, run(42, 1, NOW));
        assertThrows(IllegalArgumentException.class, () -> repository.complete(claim, failure(99)));
        var oversized = new WorkflowRunAnalysis(REPOSITORY, 42, List.of(new FailedJobAnalysis(
                job(42), LogStatus.UNAVAILABLE, "x".repeat(351 * 1024), null)));
        assertThrows(IllegalArgumentException.class, () -> repository.complete(claim, oversized));
        assertEquals(AnalysisStatus.PROCESSING, repository.findRun(REPOSITORY, 42, 1).orElseThrow().status());
        assertTrue(repository.fail(claim));
    }

    @Test
    void rejectsInvalidKeysLimitsAndLeases() {
        assertThrows(IllegalArgumentException.class, () -> repository.findRun("invalid", 42, 1));
        assertThrows(IllegalArgumentException.class, () -> repository.findRun(REPOSITORY, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> repository.findRun(REPOSITORY, 42, 0));
        assertThrows(IllegalArgumentException.class, () -> repository.recentFailures(REPOSITORY, 0));
        assertThrows(IllegalArgumentException.class, () -> repository.recentFailures(REPOSITORY, 101));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowAnalysisRepository(
                enhanced, JsonMapper.builder().build(), Clock.systemUTC(), tableName, Duration.ZERO));
    }

    @Test
    void missingTablesAreExplicitErrorsNotEmptySuccess() {
        var missing = new WorkflowAnalysisRepository(enhanced, JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC), "missing-table", LEASE);
        assertThrows(ResourceNotFoundException.class, () -> missing.findRun(REPOSITORY, 42, 1));
        assertThrows(ResourceNotFoundException.class, () -> missing.claim(REPOSITORY, run(42, 1, NOW)));
    }

    @Test
    void analysisServicePersistsResultsAndReusesThemWithoutDownloadingLogsAgain() {
        GitHubService github = mock(GitHubService.class);
        when(github.getWorkflowRun("octo", "demo", 42)).thenReturn(run(42, 1, NOW));
        when(github.listJobsForAttempt("octo", "demo", 42, 1, 1, 100))
                .thenReturn(new GitHubJobs(1, List.of(job(42))));
        when(github.getFailedJobLogs("octo", "demo", 7)).thenReturn("cannot find symbol");
        var service = new WorkflowRunAnalysisService(github, ANALYZER, repository);
        var result = service.analyze("octo", "demo", 42);
        assertEquals(result, service.analyze("octo", "demo", 42));
        assertEquals(result, repository.findRun(REPOSITORY, 42, 1).orElseThrow().analysis());
        verify(github, times(1)).listJobsForAttempt("octo", "demo", 42, 1, 1, 100);
        verify(github, times(1)).getFailedJobLogs("octo", "demo", 7);
        verify(github, times(2)).getWorkflowRun("octo", "demo", 42);
    }

    @Test
    void analysisServiceMarksFailuresRetryableAndPreservesUpstreamErrors() {
        GitHubService github = mock(GitHubService.class);
        when(github.getWorkflowRun("octo", "demo", 42)).thenReturn(run(42, 1, NOW));
        var upstream = new GitHubException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        when(github.listJobsForAttempt("octo", "demo", 42, 1, 1, 100))
                .thenThrow(upstream).thenReturn(new GitHubJobs(0, List.of()));
        var service = new WorkflowRunAnalysisService(github, ANALYZER, repository);
        assertEquals(upstream, assertThrows(GitHubException.class, () -> service.analyze("octo", "demo", 42)));
        assertEquals(AnalysisStatus.FAILED, repository.findRun(REPOSITORY, 42, 1).orElseThrow().status());
        assertEquals(empty(42), service.analyze("octo", "demo", 42));
        verify(github, never()).getFailedJobLogs("octo", "demo", 7);
    }

    private WorkflowAnalysisRepository repositoryAt(Instant time) {
        return new WorkflowAnalysisRepository(enhanced, JsonMapper.builder().build(),
                Clock.fixed(time, ZoneOffset.UTC), tableName, LEASE);
    }

    private GitHubWorkflowRun run(long id, int attempt, Instant occurredAt) {
        return new GitHubWorkflowRun(id, "CI", 3, id, attempt, "push", "completed", "failure",
                "main", "abc123", "https://github.com/" + REPOSITORY + "/actions/runs/" + id,
                occurredAt, occurredAt.plusSeconds(10), null);
    }

    private GitHubJob job(long runId) {
        return new GitHubJob(7, runId, "Build", "completed", "failure", "https://github.com/job/7",
                NOW.minusSeconds(20), NOW.minusSeconds(10), List.of());
    }

    private WorkflowRunAnalysis failure(long runId) {
        return new WorkflowRunAnalysis(REPOSITORY, runId, List.of(new FailedJobAnalysis(
                job(runId), LogStatus.AVAILABLE, null,
                ANALYZER.analyze("[ERROR] /src/App.java:[12,4] cannot find symbol"))));
    }

    private WorkflowRunAnalysis empty(long runId) {
        return new WorkflowRunAnalysis(REPOSITORY, runId, List.of());
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
