package com.githubci.intelligence.services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.githubci.intelligence.controllers.AnalyticsController;
import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubRepository;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.dto.WorkflowRunAnalysis.FailedJobAnalysis;
import com.githubci.intelligence.dto.WorkflowRunAnalysis.LogStatus;
import com.githubci.intelligence.exceptions.FailureReadException;
import com.githubci.intelligence.exceptions.GlobalExceptionHandler;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.models.StoredWorkflowAnalysis;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository.ReadPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import static com.githubci.intelligence.dto.FailureCategory.JAVA_COMPILATION;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalyticsServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant FROM = Instant.parse("2026-10-14T00:00:00Z");
    private static final GitHubRepository REPO = new GitHubRepository(1, "demo", "octo/demo", null, false);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private WorkflowAnalysisRepository repository;
    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowAnalysisRepository.class);
        service = new AnalyticsService(repository, clock);
        when(repository.repository(1)).thenReturn(Optional.of(REPO));
    }

    @Test
    void aggregatesAllPagesWithValidDenominatorAndDeduplicatedRunCounts() {
        var key = Map.of("sk", AttributeValue.builder().s("next").build());
        when(repository.runsBetween(REPO.fullName(), FROM, NOW, Map.of())).thenReturn(new ReadPage<>(List.of(
                record(1, "success", FROM, AnalysisStatus.COMPLETED, false),
                record(2, "failure", FROM.plusSeconds(1), AnalysisStatus.COMPLETED, true)), key));
        when(repository.runsBetween(REPO.fullName(), FROM, NOW, key)).thenReturn(new ReadPage<>(List.of(
                record(3, "timed_out", FROM.plusSeconds(2), AnalysisStatus.COMPLETED, true),
                record(4, "startup_failure", FROM.plusSeconds(3), AnalysisStatus.FAILED, false),
                record(5, "cancelled", FROM.plusSeconds(4), AnalysisStatus.COMPLETED, false),
                record(6, "failure", FROM.plusSeconds(5), AnalysisStatus.PROCESSING, false),
                record(7, "failure", FROM.plusSeconds(6), AnalysisStatus.COMPLETED, false),
                record(8, "success", FROM.minusSeconds(1), AnalysisStatus.COMPLETED, false),
                record(9, "failure", NOW, AnalysisStatus.COMPLETED, true)), Map.of()));
        var result = service.analytics(1, 7);
        assertEquals(7, result.totalRuns());
        assertEquals(5, result.failedRuns());
        assertEquals(2, result.successfulAnalyses());
        assertEquals(1, result.recurringFailures());
        assertEquals(2, result.failuresByCategory().get(JAVA_COMPILATION));
        assertEquals(7, result.dailyRates().size());
        assertEquals(100.0 * 5 / 7, result.dailyRates().getFirst().failureRate(), 0.0001);
        assertEquals(7, result.dailyRates().getFirst().totalRuns());
        assertNull(result.dailyRates().getLast().failureRate());
        assertEquals(0, result.dailyRates().getLast().totalRuns());
        verify(repository).runsBetween(REPO.fullName(), FROM, NOW, key);
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 30, 90})
    void returnsEveryUtcDayAndNullRatesForEmptyPeriods(int days) {
        var from = NOW.atZone(ZoneOffset.UTC).toLocalDate().minusDays(days - 1L)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        when(repository.runsBetween(REPO.fullName(), from, NOW, Map.of()))
                .thenReturn(new ReadPage<>(List.of(), Map.of()));
        var result = service.analytics(1, days);
        assertEquals(days, result.dailyRates().size());
        assertEquals(from, result.from());
        assertEquals(0, result.totalRuns());
        assertTrue(result.dailyRates().stream().allMatch(day -> day.failureRate() == null));
        assertEquals(8, result.failuresByCategory().size());
    }

    @Test
    void returnsZeroRateForSuccessOnlyDaysRatherThanAnEmptyDay() {
        when(repository.runsBetween(REPO.fullName(), FROM, NOW, Map.of())).thenReturn(new ReadPage<>(List.of(
                record(1, "success", FROM, AnalysisStatus.COMPLETED, false)), Map.of()));
        assertEquals(0.0, service.analytics(1, 7).dailyRates().getFirst().failureRate());
    }

    @Test
    void validatesSelectorsAndSurfacesDatabaseFailures() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(FailureReadException.class,
                () -> service.analytics(1, 8)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(FailureReadException.class,
                () -> service.analytics(0, 7)).getStatus());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(FailureReadException.class,
                () -> service.analytics(2, 7)).getStatus());
        when(repository.repository(1)).thenThrow(DynamoDbException.builder().message("private details").build());
        var error = assertThrows(FailureReadException.class, () -> service.analytics(1, 7));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertFalse(error.getMessage().contains("private"));
    }

    @Test
    void servesAggregateEnvelopeAndValidatesHttpParameters() throws Exception {
        when(repository.runsBetween(REPO.fullName(), FROM, NOW, Map.of()))
                .thenReturn(new ReadPage<>(List.of(), Map.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(service))
                .setControllerAdvice(new GlobalExceptionHandler(clock)).build();
        mvc.perform(get("/api/repositories/1/analytics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRuns").value(0))
                .andExpect(jsonPath("$.data.dailyRates.length()").value(7))
                .andExpect(jsonPath("$.data.dailyRates[0].failureRate").isEmpty())
                .andExpect(jsonPath("$.data.failuresByCategory.JAVA_COMPILATION").value(0))
                .andExpect(jsonPath("$.data.items").doesNotExist());
        for (String days : List.of("0", "8", "365", "invalid")) {
            mvc.perform(get("/api/repositories/1/analytics").param("days", days))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
    }

    private StoredWorkflowAnalysis record(long id, String conclusion, Instant occurred,
                                          AnalysisStatus status, boolean available) {
        var run = new GitHubWorkflowRun(id, "CI", 1, id, 1, "push", "completed", conclusion,
                "main", "sha", null, occurred, occurred, REPO);
        var job = new GitHubJob(7, id, "Build", "completed", "failure", null, occurred, occurred, List.of());
        var analysis = new FailureAnalyzerService().analyze("[ERROR] /src/App.java:[12,4] cannot find symbol");
        var failedJob = new FailedJobAnalysis(job, available ? LogStatus.AVAILABLE : LogStatus.UNAVAILABLE,
                null, available ? analysis : null, null);
        var payload = new WorkflowRunAnalysis(REPO.fullName(), id,
                com.githubci.intelligence.dto.WorkflowEvent.FAILED_CONCLUSIONS.contains(conclusion)
                        ? List.of(failedJob, failedJob) : List.of());
        return new StoredWorkflowAnalysis(REPO.fullName(), run, status,
                status == AnalysisStatus.COMPLETED ? payload : null, occurred, NOW, null);
    }
}
