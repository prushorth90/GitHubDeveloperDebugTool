package com.githubci.intelligence.repositories;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubJobs;
import com.githubci.intelligence.dto.GitHubRepository;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.dto.WorkflowRunAnalysis.FailedJobAnalysis;
import com.githubci.intelligence.dto.WorkflowRunAnalysis.LogStatus;
import com.githubci.intelligence.exceptions.AnalysisConflictException;
import com.githubci.intelligence.exceptions.GitHubException;
import com.githubci.intelligence.exceptions.GlobalExceptionHandler;
import com.githubci.intelligence.controllers.RepositoryController;
import com.githubci.intelligence.controllers.AnalyticsController;
import com.githubci.intelligence.services.AnalyticsService;
import com.githubci.intelligence.services.FailureService;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.services.FailureAnalyzerService;
import com.githubci.intelligence.services.GitHubService;
import com.githubci.intelligence.services.LogRedactionService;
import com.githubci.intelligence.services.LogStorageService;
import com.githubci.intelligence.services.WorkflowRunAnalysisService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

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
    private MockMvc mvc;

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
                .attributeDefinitions(attribute("pk"), attribute("sk"), attribute("failurePk"), attribute("failureSk"),
                        attribute("runSk"))
                .keySchema(key("pk", KeyType.HASH), key("sk", KeyType.RANGE))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(WorkflowAnalysisRepository.RECENT_FAILURES_INDEX)
                        .keySchema(key("failurePk", KeyType.HASH), key("failureSk", KeyType.RANGE))
                        .projection(projection -> projection.projectionType(ProjectionType.ALL)).build(),
                        GlobalSecondaryIndex.builder().indexName(WorkflowAnalysisRepository.RUNS_INDEX)
                                .keySchema(key("pk", KeyType.HASH), key("runSk", KeyType.RANGE))
                                .projection(projection -> projection.projectionType(ProjectionType.ALL)).build()));
        client.waiter().waitUntilTableExists(request -> request.tableName(tableName));
        repository = repositoryAt(NOW);
        mvc = MockMvcBuilders.standaloneSetup(new RepositoryController(
                        new FailureService(repository, JsonMapper.builder().build())),
                        new AnalyticsController(new AnalyticsService(repository, Clock.fixed(NOW, ZoneOffset.UTC))))
                .setControllerAdvice(new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC))).build();
    }

    @AfterEach
    void deleteTable() {
        client.deleteTable(request -> request.tableName(tableName));
    }

    @Test
    void queriesOnlyTheRequestedAnalyticsTimeRangeInChronologicalOrder() {
        repository.claim(REPOSITORY, run(1, 1, NOW.minusSeconds(100)));
        repository.claim(REPOSITORY, run(2, 1, NOW.minusSeconds(50)));
        repository.claim(REPOSITORY, run(3, 1, NOW.minusSeconds(25)));
        repository.claim(REPOSITORY, run(4, 1, NOW));
        var page = repository.runsBetween(REPOSITORY, NOW.minusSeconds(50), NOW, Map.of());
        assertEquals(List.of(2L, 3L), page.items().stream().map(record -> record.workflowRun().id()).toList());
        assertTrue(page.nextKey().isEmpty());
    }

    @Test
    void returnsPersistedSuccessesInTheAnalyticsDenominatorWithoutRawHistory() throws Exception {
        var success = new GitHubWorkflowRun(1, "CI", 1, 1, 1, "push", "completed", "success",
                "main", "sha", null, NOW.minusSeconds(60), NOW, run(42, 1, NOW).repository());
        repository.complete(repository.claim(REPOSITORY, success), empty(1));
        repository.complete(repository.claim(REPOSITORY, run(42, 1, NOW.minusSeconds(30))), failure(42));
        mvc.perform(get("/api/repositories/" + success.repository().id() + "/analytics").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRuns").value(2))
                .andExpect(jsonPath("$.data.failedRuns").value(1))
                .andExpect(jsonPath("$.data.successfulAnalyses").value(1))
                .andExpect(jsonPath("$.data.dailyRates[6].failureRate").value(50.0))
                .andExpect(jsonPath("$.data.dailyRates.length()").value(7))
                .andExpect(jsonPath("$.data.items").doesNotExist())
                .andExpect(jsonPath("$.data.workflowRun").doesNotExist());
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
    void persistsBedrockProvenanceAndFindingsThroughTheExistingRestDetail() throws Exception {
        var base = failure(42);
        var failedJob = base.failedJobs().getFirst();
        var javaAnalysis = failedJob.analysis();
        var explanation = new com.githubci.intelligence.dto.FailureAnalysis(
                javaAnalysis.category(), javaAnalysis.errorMessage(), javaAnalysis.suspectedCause(),
                javaAnalysis.suggestedInvestigation(), javaAnalysis.confidence(), javaAnalysis.evidence(),
                javaAnalysis.logContext(), javaAnalysis.normalizedSignature(), javaAnalysis.fingerprint(),
                "BEDROCK", "mock-model", javaAnalysis.diagnosticFindings(), null);
        var enriched = new WorkflowRunAnalysis(base.repository(), base.runId(), List.of(
                new FailedJobAnalysis(failedJob.job(), failedJob.logStatus(), failedJob.logMessage(),
                        explanation, failedJob.logObjectKey())));
        var run = run(42, 1, NOW);
        repository.complete(repository.claim(REPOSITORY, run), enriched);
        assertEquals(enriched, repository.findRun(REPOSITORY, 42, 1).orElseThrow().analysis());
        mvc.perform(get("/api/repositories/" + run.repository().id() + "/failures/42").param("attempt", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failedJobs[0].analysis.analysisSource").value("BEDROCK"))
                .andExpect(jsonPath("$.data.failedJobs[0].analysis.modelIdentifier").value("mock-model"))
                .andExpect(jsonPath("$.data.failedJobs[0].analysis.diagnosticFindings[0].kind").value("OBSERVATION"))
                .andExpect(jsonPath("$.data.failedJobs[0].analysis.diagnosticFindings[0].evidence[0].lineNumber")
                        .value(javaAnalysis.evidence().getFirst().lineNumber()));
    }

    @Test
    void renewalExtendsLeaseWithoutLosingMetadataAndFencesOldOwners() {
        var run = run(42, 1, NOW);
        var claim = repository.claim(REPOSITORY, run);
        repositoryAt(NOW.plusSeconds(200)).renew(claim);
        assertEquals(NOW.plusSeconds(500), repository.findRun(REPOSITORY, 42, 1).orElseThrow().leaseUntil());
        assertEquals(run, repository.findRun(REPOSITORY, 42, 1).orElseThrow().workflowRun());
        assertThrows(AnalysisConflictException.class, () -> repositoryAt(NOW.plusSeconds(301)).claim(REPOSITORY, run));
        var takeover = repositoryAt(NOW.plusSeconds(501)).claim(REPOSITORY, run);
        assertThrows(AnalysisConflictException.class, () -> repositoryAt(NOW.plusSeconds(502)).renew(claim));
        repositoryAt(NOW.plusSeconds(502)).complete(takeover, empty(42));
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
                List.of(new FailedJobAnalysis(job, LogStatus.UNAVAILABLE, "Logs expired", null, null)));
        repository.complete(repository.claim(REPOSITORY, run(42, 1, NOW)), result);
        assertEquals(result, repository.findRun(REPOSITORY, 42, 1).orElseThrow().analysis());
        assertEquals(1, repository.recentFailures(REPOSITORY, 10).size());
    }

    @Test
    void rejectsMismatchedAndOversizedResultsWithoutPartialCompletion() {
        var claim = repository.claim(REPOSITORY, run(42, 1, NOW));
        assertThrows(IllegalArgumentException.class, () -> repository.complete(claim, failure(99)));
        var oversized = new WorkflowRunAnalysis(REPOSITORY, 42, List.of(new FailedJobAnalysis(
                job(42), LogStatus.UNAVAILABLE, "x".repeat(351 * 1024), null, null)));
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
        var storage = mock(LogStorageService.class);
        when(storage.upload(1, 42, 1, 7, "cannot find symbol"))
                .thenReturn(LogStorageService.objectKey(1, 42, 1, 7));
        var service = new WorkflowRunAnalysisService(github, ANALYZER, repository,
                storage, new LogRedactionService("", ""));
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
        var service = new WorkflowRunAnalysisService(github, ANALYZER, repository,
                mock(LogStorageService.class), new LogRedactionService("", ""));
        assertEquals(upstream, assertThrows(GitHubException.class, () -> service.analyze("octo", "demo", 42)));
        assertEquals(AnalysisStatus.FAILED, repository.findRun(REPOSITORY, 42, 1).orElseThrow().status());
        assertEquals(empty(42), service.analyze("octo", "demo", 42));
        verify(github, never()).getFailedJobLogs("octo", "demo", 7);
    }

    @Test
    void repositoryEndpointListsPersistedMetadataAndPaginates() throws Exception {
        repository.claim(REPOSITORY, run(42, 1, NOW));
        var other = new GitHubWorkflowRun(43, "CI", 3, 43, 1, "push", "completed", "success",
                "main", "sha", null, NOW, NOW,
                new GitHubRepository(2, "other", "octo/other", null, false));
        repository.claim("octo/other", other);
        String body = mvc.perform(get("/api/repositories").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(1))
                .andExpect(jsonPath("$.data.items[0].fullName").value(REPOSITORY))
                .andExpect(jsonPath("$.data.items[0].pk").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = JsonMapper.builder().build().readTree(body).path("data").path("nextToken").asString();
        mvc.perform(get("/api/repositories").param("limit", "1").param("nextToken", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(2));
    }

    @Test
    void runsEndpointUsesChronologyRatherThanRunIdAndKeepsAttemptsDistinct() throws Exception {
        repository.complete(repository.claim(REPOSITORY, run(99, 1, NOW.minusSeconds(90))), empty(99));
        repository.complete(repository.claim(REPOSITORY, run(42, 1, NOW)), failure(42));
        repository.claim(REPOSITORY, run(42, 2, NOW));
        String body = mvc.perform(get("/api/repositories/1/runs").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].runId").value(42))
                .andExpect(jsonPath("$.data.items[0].attempt").value(2))
                .andExpect(jsonPath("$.data.items[0].analysisStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data.items[0].analysisComplete").value(false))
                .andReturn().getResponse().getContentAsString();
        String token = JsonMapper.builder().build().readTree(body).path("data").path("nextToken").asString();
        mvc.perform(get("/api/repositories/1/runs").param("limit", "1").param("nextToken", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].attempt").value(1));
        mvc.perform(get("/api/repositories/1/runs").param("order", "asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].runId").value(99));
        mvc.perform(get("/api/repositories/1/runs").param("category", "JAVA_COMPILATION"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void failuresEndpointFiltersAndCanReturnEmptyPagesWithContinuation() throws Exception {
        repository.complete(repository.claim(REPOSITORY, run(42, 1, NOW)), failure(42));
        repository.complete(repository.claim(REPOSITORY, run(43, 1, NOW.plusSeconds(10))),
                new WorkflowRunAnalysis(REPOSITORY, 43, List.of(new FailedJobAnalysis(
                        job(43), LogStatus.UNAVAILABLE, "Expired", null, null))));
        repository.complete(repository.claim(REPOSITORY, run(44, 1, NOW.plusSeconds(20))), empty(44));
        String body = mvc.perform(get("/api/repositories/1/failures")
                        .param("category", "JAVA_COMPILATION").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextToken").isString())
                .andReturn().getResponse().getContentAsString();
        String token = JsonMapper.builder().build().readTree(body).path("data").path("nextToken").asString();
        mvc.perform(get("/api/repositories/1/failures").param("category", "JAVA_COMPILATION")
                        .param("limit", "1").param("nextToken", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].runId").value(42));
        mvc.perform(get("/api/repositories/1/failures"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    void detailEndpointHandlesLatestAttemptsPartialAnalysisAndMissingRecords() throws Exception {
        repository.complete(repository.claim(REPOSITORY, run(42, 1, NOW)), failure(42));
        mvc.perform(get("/api/repositories/1/failures/42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.failedJobs[0].analysis.category")
                        .value("JAVA_COMPILATION"))
                .andExpect(jsonPath("$.data.failedJobs[0].logObjectKey").doesNotExist())
                .andExpect(jsonPath("$.data.run.analysisComplete").value(true));
        var second = repository.claim(REPOSITORY, run(42, 2, NOW));
        mvc.perform(get("/api/repositories/1/failures/42")).andExpect(status().isConflict());
        mvc.perform(get("/api/repositories/1/failures/42").param("attempt", "1")).andExpect(status().isOk());
        repository.fail(second);
        mvc.perform(get("/api/repositories/1/failures/42")).andExpect(status().isConflict());
        repository.complete(repository.claim(REPOSITORY, run(42, 2, NOW)),
                new WorkflowRunAnalysis(REPOSITORY, 42, List.of(new FailedJobAnalysis(
                        job(42), LogStatus.UNAVAILABLE, "Expired", null, null))));
        mvc.perform(get("/api/repositories/1/failures/42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.run.analysisComplete").value(false))
                .andExpect(jsonPath("$.data.failedJobs[0].logMessage").value("Expired"));
        mvc.perform(get("/api/repositories/1/failures/999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories/999/statistics")).andExpect(status().isNotFound());
    }

    @Test
    void statisticsEndpointAggregatesEveryPageIncludingProcessingAndUnavailableAnalyses() throws Exception {
        for (int i = 1; i <= 102; i++) {
            repository.complete(repository.claim(REPOSITORY, run(i, 1, NOW)), failure(i));
        }
        repository.claim(REPOSITORY, run(200, 1, NOW));
        repository.fail(repository.claim(REPOSITORY, run(201, 1, NOW)));
        repository.complete(repository.claim(REPOSITORY, run(202, 1, NOW)),
                new WorkflowRunAnalysis(REPOSITORY, 202, List.of(new FailedJobAnalysis(
                        job(202), LogStatus.UNAVAILABLE, "Expired", null, null))));
        mvc.perform(get("/api/repositories/1/statistics"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRuns").value(105))
                .andExpect(jsonPath("$.data.completedAnalyses").value(103))
                .andExpect(jsonPath("$.data.processingAnalyses").value(1))
                .andExpect(jsonPath("$.data.failedAnalyses").value(1))
                .andExpect(jsonPath("$.data.failureRuns").value(103))
                .andExpect(jsonPath("$.data.unavailableJobAnalyses").value(1))
                .andExpect(jsonPath("$.data.failuresByCategory.JAVA_COMPILATION").value(102));
    }

    @Test
    void similarEndpointMatchesFingerprintsWithoutReturningSourceOrOtherRepositories() throws Exception {
        repository.complete(repository.claim(REPOSITORY, run(42, 1, NOW)), failure(42));
        repository.complete(repository.claim(REPOSITORY, run(42, 2, NOW)), failure(42));
        repository.complete(repository.claim(REPOSITORY, run(43, 1, NOW.minusSeconds(10))), failure(43));
        repository.complete(repository.claim(REPOSITORY, run(44, 1, NOW.minusSeconds(20))),
                new WorkflowRunAnalysis(REPOSITORY, 44, List.of(new FailedJobAnalysis(
                        job(44), LogStatus.AVAILABLE, null, ANALYZER.analyze("unrelated failure"), null))));
        var other = new GitHubWorkflowRun(45, "CI", 3, 45, 1, "push", "completed", "failure",
                "main", "sha", null, NOW, NOW,
                new GitHubRepository(2, "other", "octo/other", null, false));
        repository.complete(repository.claim("octo/other", other),
                new WorkflowRunAnalysis("octo/other", 45, failure(45).failedJobs()));
        mvc.perform(get("/api/repositories/1/failures/42/similar"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].runId").value(43));
        String body = mvc.perform(get("/api/repositories/1/failures/42/similar").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextToken").isString())
                .andReturn().getResponse().getContentAsString();
        String token = JsonMapper.builder().build().readTree(body).path("data").path("nextToken").asString();
        mvc.perform(get("/api/repositories/1/failures/42/similar").param("limit", "1").param("nextToken", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextToken").isString());
    }

    @Test
    void endpointsReturnStandardErrorsForInvalidParametersAndDatabaseFailures() throws Exception {
        repository.claim(REPOSITORY, run(42, 1, NOW));
        for (String path : List.of("/runs", "/failures", "/failures/42", "/statistics", "/failures/42/similar")) {
            mvc.perform(get("/api/repositories/999" + path))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Repository not found"));
        }
        for (String suffix : List.of("/runs?limit=0", "/failures?category=INVALID",
                "/runs?order=invalid", "/runs?nextToken=bad", "/failures/42?attempt=0",
                "/failures/42/similar?limit=101")) {
            mvc.perform(get("/api/repositories/1" + suffix))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.path").exists());
        }
        var missing = new WorkflowAnalysisRepository(enhanced, JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC), "missing-" + UUID.randomUUID(), LEASE);
        var unavailableMvc = MockMvcBuilders.standaloneSetup(new RepositoryController(
                        new FailureService(missing, JsonMapper.builder().build())))
                .setControllerAdvice(new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC))).build();
        for (String path : List.of("", "/1/runs", "/1/failures", "/1/failures/42",
                "/1/statistics", "/1/failures/42/similar")) {
            unavailableMvc.perform(get("/api/repositories" + path))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.message").value("Analysis database is temporarily unavailable"));
        }
    }

    @Test
    void backfillMakesLegacyRecordsVisibleAndIsIdempotent() {
        repository.complete(repository.claim(REPOSITORY, run(42, 1, NOW)), failure(42));
        var legacy = client.scan(request -> request.tableName(tableName)).items().stream()
                .filter(item -> item.get("pk").s().startsWith("REPO#")).findFirst().orElseThrow();
        var key = Map.of("pk", legacy.get("pk"), "sk", legacy.get("sk"));
        client.updateItem(request -> request.tableName(tableName).key(key).updateExpression("REMOVE runSk"));
        client.deleteItem(request -> request.tableName(tableName).key(Map.of(
                "pk", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s("REPOSITORIES").build(),
                "sk", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                        .s("REPOSITORY#00000000000000000001").build())));
        assertTrue(repository.repository(1).isEmpty());
        assertTrue(repository.runs(REPOSITORY, false, false, 20, Map.of()).items().isEmpty());
        assertEquals(1, repository.backfillReadMetadata());
        assertEquals(1, repository.backfillReadMetadata());
        assertEquals(1, repository.repositories(20, Map.of()).items().size());
        assertEquals(1, repository.runs(REPOSITORY, false, false, 20, Map.of()).items().size());
        assertEquals(failure(42), repository.findRun(REPOSITORY, 42, 1).orElseThrow().analysis());
    }

    private WorkflowAnalysisRepository repositoryAt(Instant time) {
        return new WorkflowAnalysisRepository(enhanced, JsonMapper.builder().build(),
                Clock.fixed(time, ZoneOffset.UTC), tableName, LEASE);
    }

    private GitHubWorkflowRun run(long id, int attempt, Instant occurredAt) {
        return new GitHubWorkflowRun(id, "CI", 3, id, attempt, "push", "completed", "failure",
                "main", "abc123", "https://github.com/" + REPOSITORY + "/actions/runs/" + id,
                occurredAt, occurredAt.plusSeconds(10),
                new GitHubRepository(1, "demo", REPOSITORY, "https://github.com/" + REPOSITORY, true));
    }

    private GitHubJob job(long runId) {
        return new GitHubJob(7, runId, "Build", "completed", "failure", "https://github.com/job/7",
                NOW.minusSeconds(20), NOW.minusSeconds(10), List.of());
    }

    private WorkflowRunAnalysis failure(long runId) {
        return new WorkflowRunAnalysis(REPOSITORY, runId, List.of(new FailedJobAnalysis(
                job(runId), LogStatus.AVAILABLE, null,
                ANALYZER.analyze("[ERROR] /src/App.java:[12,4] cannot find symbol"),
                LogStorageService.objectKey(1, runId, 1, 7))));
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
