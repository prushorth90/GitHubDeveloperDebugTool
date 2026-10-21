package com.githubci.intelligence.services;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubRepository;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.dto.WorkflowRunAnalysis.FailedJobAnalysis;
import com.githubci.intelligence.dto.WorkflowRunAnalysis.LogStatus;
import com.githubci.intelligence.exceptions.FailureReadException;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.models.StoredWorkflowAnalysis;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository.ReadPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import tools.jackson.databind.json.JsonMapper;

import static com.githubci.intelligence.dto.FailureCategory.JAVA_COMPILATION;
import static com.githubci.intelligence.dto.FailureCategory.JUNIT_TEST;
import static com.githubci.intelligence.services.FailureService.Order.asc;
import static com.githubci.intelligence.services.FailureService.Order.desc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FailureServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T07:00:00Z");
    private static final GitHubRepository REPO = new GitHubRepository(1, "demo", "octo/demo", "https://github.com/octo/demo", true);
    private WorkflowAnalysisRepository repository;
    private FailureService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowAnalysisRepository.class);
        service = new FailureService(repository, JsonMapper.builder().build());
        lenient().when(repository.repository(1)).thenReturn(Optional.of(REPO));
    }

    @Test
    void listsRepositoriesUsingDtosAndTerminalToken() {
        when(repository.repositories(20, Map.of())).thenReturn(new ReadPage<>(List.of(REPO), Map.of()));
        var result = service.repositories(20, null);
        assertEquals(1, result.items().getFirst().id());
        assertEquals(REPO.fullName(), result.items().getFirst().fullName());
        assertNull(result.nextToken());
    }

    @Test
    void listsRunsWithCategoryFilteringAndRequestedOrdering() {
        when(repository.runs(REPO.fullName(), false, true, 20, Map.of()))
                .thenReturn(new ReadPage<>(List.of(record(42, AnalysisStatus.COMPLETED, true),
                        record(43, AnalysisStatus.PROCESSING, false)), Map.of()));
        var result = service.runs(1, false, JAVA_COMPILATION, asc, 20, null);
        assertEquals(1, result.items().size());
        assertEquals(42, result.items().getFirst().runId());
        assertTrue(result.items().getFirst().analysisComplete());
        assertEquals(List.of(JAVA_COMPILATION), result.items().getFirst().categories());
    }

    @Test
    void listsFailuresWithCursorEvenWhenFilterExcludesEntirePage() {
        var key = Map.of("pk", value("REPO#octo/demo"),
                "sk", value("RUN#00000000000000000042#ATTEMPT#0000000001"),
                "failurePk", value("REPO#octo/demo"),
                "failureSk", value("2026-10-20T07:00:00.000Z#RUN#00000000000000000042#ATTEMPT#0000000001"));
        when(repository.runs(REPO.fullName(), true, false, 1, Map.of()))
                .thenReturn(new ReadPage<>(List.of(record(42, AnalysisStatus.COMPLETED, true)), key));
        when(repository.runs(REPO.fullName(), true, false, 1, key))
                .thenReturn(new ReadPage<>(List.of(), Map.of()));
        var first = service.runs(1, true, JUNIT_TEST, desc, 1, null);
        assertTrue(first.items().isEmpty());
        assertNotNull(first.nextToken());
        assertNull(service.runs(1, true, JUNIT_TEST, desc, 1, first.nextToken()).nextToken());
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.runs(1, true, null, desc, 1, first.nextToken()));
        when(repository.repository(2)).thenReturn(Optional.of(
                new GitHubRepository(2, "other", "octo/other", null, false)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.runs(2, true, JUNIT_TEST, desc, 1, first.nextToken()));
    }

    @Test
    void returnsFailureDetailsAndSelectsLatestOrExplicitAttempt() {
        var record = record(42, AnalysisStatus.COMPLETED, true);
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(record));
        when(repository.findRun(REPO.fullName(), 42, 1)).thenReturn(Optional.of(record));
        assertEquals(JAVA_COMPILATION, service.failure(1, 42, null).failedJobs().getFirst().analysis().category());
        assertEquals(1, service.failure(1, 42, 1).run().attempt());
        verify(repository).latestRun(REPO.fullName(), 42);
        verify(repository).findRun(REPO.fullName(), 42, 1);
    }

    @Test
    void preservesPartialAnalysisWithoutInventingCategoryOrFingerprint() {
        var record = record(42, AnalysisStatus.COMPLETED, false);
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(record));
        var detail = service.failure(1, 42, null);
        assertFalse(detail.run().analysisComplete());
        assertTrue(detail.run().categories().isEmpty());
        assertNull(detail.failedJobs().getFirst().analysis());
        assertEquals("Logs expired", detail.failedJobs().getFirst().logMessage());
        assertStatus(HttpStatus.CONFLICT, () -> service.similar(1, 42, null, null, desc, 20, null));
    }

    @Test
    void reportsMissingRepositoryRunAndFailure() {
        assertStatus(HttpStatus.NOT_FOUND, () -> service.statistics(999));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.failure(1, 999, null));
        var empty = record(42, AnalysisStatus.COMPLETED, true);
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(new StoredWorkflowAnalysis(
                empty.repository(), empty.workflowRun(), empty.status(),
                new WorkflowRunAnalysis(REPO.fullName(), 42, List.of()), NOW, NOW, null)));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.failure(1, 42, null));
    }

    @Test
    void doesNotReturnProcessingFailedOrCorruptResultsAsSuccessfulAnalyses() {
        for (var status : List.of(AnalysisStatus.PROCESSING, AnalysisStatus.FAILED)) {
            when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(record(42, status, false)));
            assertStatus(HttpStatus.CONFLICT, () -> service.failure(1, 42, null));
        }
        var valid = record(42, AnalysisStatus.COMPLETED, true);
        var corrupt = new StoredWorkflowAnalysis(valid.repository(), valid.workflowRun(), valid.status(),
                null, NOW, NOW, null);
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(corrupt));
        assertStatus(HttpStatus.INTERNAL_SERVER_ERROR, () -> service.failure(1, 42, null));
    }

    @Test
    void similarUsesExactFingerprintExcludesSourceAndHonorsCategory() {
        var source = record(42, AnalysisStatus.COMPLETED, true);
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(source));
        when(repository.runs(REPO.fullName(), true, false, 20, Map.of()))
                .thenReturn(new ReadPage<>(List.of(source, record(43, AnalysisStatus.COMPLETED, true),
                        record(44, AnalysisStatus.COMPLETED, false)), Map.of()));
        var result = service.similar(1, 42, null, JAVA_COMPILATION, desc, 20, null);
        assertEquals(List.of(43L), result.items().stream().map(run -> run.runId()).toList());
        assertTrue(service.similar(1, 42, null, JUNIT_TEST, desc, 20, null).items().isEmpty());
    }

    @Test
    void aggregatesStatisticsAcrossPagesWithoutCountingUnknownLogsAsUnknownFailures() {
        var key = Map.of("pk", value("REPO#octo/demo"), "sk", value("run"), "runSk", value("time"));
        when(repository.runs(REPO.fullName(), false, false, 100, Map.of()))
                .thenReturn(new ReadPage<>(List.of(record(42, AnalysisStatus.COMPLETED, true),
                        record(43, AnalysisStatus.PROCESSING, false)), key));
        when(repository.runs(REPO.fullName(), false, false, 100, key))
                .thenReturn(new ReadPage<>(List.of(record(44, AnalysisStatus.COMPLETED, false),
                        record(45, AnalysisStatus.FAILED, false)), Map.of()));
        var stats = service.statistics(1);
        assertEquals(4, stats.totalRuns());
        assertEquals(2, stats.completedAnalyses());
        assertEquals(1, stats.processingAnalyses());
        assertEquals(1, stats.failedAnalyses());
        assertEquals(2, stats.failureRuns());
        assertEquals(1, stats.unavailableJobAnalyses());
        assertEquals(1, stats.failuresByCategory().get(JAVA_COMPILATION));
        assertEquals(0, stats.failuresByCategory().get(JUNIT_TEST));
        assertEquals(42, stats.latestRun().runId());
        assertEquals(NOW, stats.lastAnalyzedAt());
    }

    @Test
    void findsLastAnalysisAcrossAllPagesEvenWhenLatestWorkflowIsStillProcessing() {
        var key = Map.of("pk", value("REPO#octo/demo"), "sk", value("run"), "runSk", value("time"));
        var completed = record(41, AnalysisStatus.COMPLETED, true);
        var laterAnalysis = NOW.plusSeconds(300);
        when(repository.runs(REPO.fullName(), false, false, 100, Map.of()))
                .thenReturn(new ReadPage<>(List.of(record(43, AnalysisStatus.PROCESSING, false),
                        record(42, AnalysisStatus.COMPLETED, true)), key));
        when(repository.runs(REPO.fullName(), false, false, 100, key))
                .thenReturn(new ReadPage<>(List.of(new StoredWorkflowAnalysis(
                        completed.repository(), completed.workflowRun(), completed.status(), completed.analysis(),
                        completed.createdAt(), laterAnalysis, null)), Map.of()));
        var stats = service.statistics(1);
        assertEquals(43, stats.latestRun().runId());
        assertEquals(AnalysisStatus.PROCESSING, stats.latestRun().analysisStatus());
        assertNull(stats.latestRun().analyzedAt());
        assertEquals(laterAnalysis, stats.lastAnalyzedAt());
    }

    @Test
    void emptyRepositoryHasNoLatestRunOrAnalysisTime() {
        when(repository.runs(REPO.fullName(), false, false, 100, Map.of()))
                .thenReturn(new ReadPage<>(List.of(), Map.of()));
        var stats = service.statistics(1);
        assertEquals(0, stats.totalRuns());
        assertNull(stats.latestRun());
        assertNull(stats.lastAnalyzedAt());
    }

    @Test
    void failedAnalysisDoesNotCountAsLastAnalyzed() {
        when(repository.runs(REPO.fullName(), false, false, 100, Map.of()))
                .thenReturn(new ReadPage<>(List.of(record(42, AnalysisStatus.FAILED, false)), Map.of()));
        var stats = service.statistics(1);
        assertEquals(42, stats.latestRun().runId());
        assertNull(stats.lastAnalyzedAt());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101, Integer.MAX_VALUE})
    void rejectsInvalidPageLimitsBeforeAccessingDatabase(int limit) {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.repositories(limit, null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.runs(1, false, null, desc, limit, null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.similar(1, 42, null, null, desc, limit, null));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidIdsAttemptsAndOrder() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.failure(1, 0, null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.failure(1, 42, 0));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.statistics(0));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.runs(1, false, null, null, 20, null));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bad", "bnVsbA", "e30"})
    void rejectsMalformedPaginationTokens(String token) {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.repositories(20, token));
        verifyNoInteractions(repository);
    }

    @Test
    void translatesDatabaseErrorsWithoutLeakingDetailsOrReturningEmptySuccess() {
        when(repository.repository(1)).thenThrow(DynamoDbException.builder().message("internal table secret").build());
        var error = assertThrows(FailureReadException.class, () -> service.statistics(1));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertFalse(error.getMessage().contains("secret"));
        assertNotNull(error.getCause());
    }

    private StoredWorkflowAnalysis record(long id, AnalysisStatus status, boolean available) {
        var run = new GitHubWorkflowRun(id, "CI", 1, id, 1, "push", "completed", "failure",
                "main", "sha", null, NOW, NOW, REPO);
        var job = new GitHubJob(7, id, "Build", "completed", "failure", null, NOW, NOW, List.of());
        var analysis = status != AnalysisStatus.COMPLETED ? null : new WorkflowRunAnalysis(
                REPO.fullName(), id, List.of(new FailedJobAnalysis(job,
                        available ? LogStatus.AVAILABLE : LogStatus.UNAVAILABLE,
                        available ? null : "Logs expired",
                        available ? new FailureAnalyzerService().analyze(
                                "[ERROR] /src/App.java:[12,4] cannot find symbol") : null,
                        available ? "private-log-key" : null)));
        return new StoredWorkflowAnalysis(REPO.fullName(), run, status, analysis, NOW, NOW, null);
    }

    private static AttributeValue value(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(expected, assertThrows(FailureReadException.class, operation).getStatus());
    }
}
