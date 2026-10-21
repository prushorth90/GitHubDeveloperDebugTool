package com.githubci.intelligence.controllers;

import java.io.IOException;
import java.time.Instant;

import com.githubci.intelligence.configuration.GitHubConfiguration;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.exceptions.AnalysisConflictException;
import com.githubci.intelligence.exceptions.LogStorageException;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.models.StoredWorkflowAnalysis;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import com.githubci.intelligence.services.GitHubService;
import com.githubci.intelligence.services.LogStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.profiles.active=local", "github.token=", "app.bedrock.enabled=false"})
@Import(WorkflowRunAnalysisIntegrationTest.MockGitHubConfiguration.class)
class WorkflowRunAnalysisIntegrationTest {

    private static final String ROOT = "https://api.github.com/repos/octo/demo/actions";
    private static final String PATH = "/api/github/repositories/octo/demo/runs/42/analyze";
    private static final String DOWNLOAD = "https://logs.actions.githubusercontent.com/job.txt?sig=test";
    private static final String COMPILER_ERROR = "[ERROR] /src/App.java:[12,4] cannot find symbol";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private MockGitHubConfiguration github;
    private MockMvc mvc;
    @MockitoBean
    private WorkflowAnalysisRepository repository;
    @MockitoBean
    private LogStorageService logStorage;

    @BeforeEach
    void setUp() {
        github.api.reset();
        github.downloads.reset();
        when(repository.claim(anyString(), any(GitHubWorkflowRun.class))).thenAnswer(invocation ->
                new WorkflowAnalysisRepository.Claim(new StoredWorkflowAnalysis(
                        invocation.getArgument(0), invocation.getArgument(1), AnalysisStatus.PROCESSING,
                        null, Instant.now(), Instant.now(), Instant.now().plusSeconds(300)), "test-token"));
        when(repository.fail(any())).thenReturn(true);
        when(logStorage.upload(anyLong(), anyLong(), anyInt(), anyLong(), anyString()))
                .thenAnswer(invocation -> LogStorageService.objectKey(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3)));
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void verifyRequests() {
        github.api.verify();
        github.downloads.verify();
    }

    @Test
    void analyzesFailedAndTimedOutJobsAcrossPagesSynchronously() throws Exception {
        expectJobs(1, 3, job(7, "failure"), job(8, "success"));
        expectJob(7, "failure");
        github.api.expect(requestTo(ROOT + "/jobs/7/logs"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer integration-test-token"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, DOWNLOAD));
        github.downloads.expect(requestTo(DOWNLOAD))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("2026-10-17T03:00:00Z " + COMPILER_ERROR, MediaType.TEXT_PLAIN));
        expectJobs(2, 3, job(9, "timed_out"));
        expectLogs(9, "timed_out", "fatal: unknown build error");

        mvc.perform(post(PATH))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals(false, result.getRequest().isAsyncStarted()))
                .andExpect(jsonPath("$.repository").value("octo/demo"))
                .andExpect(jsonPath("$.runId").value(42))
                .andExpect(jsonPath("$.failedJobs.length()").value(2))
                .andExpect(jsonPath("$.failedJobs[0].job.id").value(7))
                .andExpect(jsonPath("$.failedJobs[0].job.run_id").value(42))
                .andExpect(jsonPath("$.failedJobs[0].job.name").value("job-7"))
                .andExpect(jsonPath("$.failedJobs[0].job.steps[0].name").value("Build"))
                .andExpect(jsonPath("$.failedJobs[0].logStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.failedJobs[0].logObjectKey")
                        .value("logs/repositories/1/runs/42/attempts/1/jobs/7.log.gz"))
                .andExpect(jsonPath("$.failedJobs[0].analysis.errorMessage").value(COMPILER_ERROR))
                .andExpect(jsonPath("$.failedJobs[0].analysis.category").value("JAVA_COMPILATION"))
                .andExpect(jsonPath("$.failedJobs[0].analysis.confidence").value("HIGH"))
                .andExpect(jsonPath("$.failedJobs[0].analysis.analysisSource").value("JAVA"))
                .andExpect(jsonPath("$.failedJobs[0].analysis.diagnosticFindings[0].kind").value("OBSERVATION"))
                .andExpect(jsonPath("$.failedJobs[0].analysis.diagnosticFindings[1].confidence").value("LOW"))
                .andExpect(jsonPath("$.failedJobs[0].analysis.evidence[0].text").value(COMPILER_ERROR))
                .andExpect(jsonPath("$.failedJobs[0].analysis.normalizedSignature").value(COMPILER_ERROR))
                .andExpect(jsonPath("$.failedJobs[0].analysis.fingerprint").value(matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.failedJobs[0].analysis.suggestedInvestigation").isNotEmpty())
                .andExpect(jsonPath("$.failedJobs[1].job.conclusion").value("timed_out"))
                .andExpect(jsonPath("$.failedJobs[1].analysis.category").value("UNKNOWN"))
                .andExpect(jsonPath("$.failedJobs[1].analysis.confidence").value("LOW"));
    }

    @Test
    void sanitizesLogsBeforeUploadAndPersistingAnalysisAndKeepsFullLogsOutOfItems() throws Exception {
        String logs = "Authorization: Bearer do-not-store\npassword=do-not-store\n"
                + "[ERROR] failed " + "x".repeat(400_000);
        expectJobs(1, 1, job(7, "failure"));
        expectLogs(7, "failure", logs);
        mvc.perform(post(PATH)).andExpect(status().isOk());

        var uploaded = ArgumentCaptor.forClass(String.class);
        verify(logStorage).upload(eq(1L), eq(42L), eq(1), eq(7L), uploaded.capture());
        assertFalse(uploaded.getValue().contains("do-not-store"));
        assertFalse(uploaded.getValue().contains("Authorization"));
        assertTrue(uploaded.getValue().length() > 400_000);

        var persisted = ArgumentCaptor.forClass(WorkflowRunAnalysis.class);
        verify(repository).complete(any(), persisted.capture());
        String json = JsonMapper.builder().build().writeValueAsString(persisted.getValue());
        assertFalse(json.contains("do-not-store"));
        assertFalse(json.contains("Authorization"));
        assertTrue(json.length() < 20_000);
        assertEquals(LogStorageService.objectKey(1, 42, 1, 7),
                persisted.getValue().failedJobs().getFirst().logObjectKey());
    }

    @Test
    void storageFailureMarksAnalysisRetryableWithoutPersistingACompletedReference() throws Exception {
        expectJobs(1, 1, job(7, "failure"));
        expectLogs(7, "failure", COMPILER_ERROR);
        doThrow(new LogStorageException(HttpStatus.SERVICE_UNAVAILABLE, "Could not upload job logs"))
                .when(logStorage).upload(anyLong(), anyLong(), anyInt(), anyLong(), anyString());
        mvc.perform(post(PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Could not upload job logs"));
        verify(repository).fail(any());
        verify(repository, never()).complete(any(), any());
    }

    @Test
    void returnsEmptyFailuresWithoutDownloadingSuccessfulSkippedCancelledOrPendingJobs() throws Exception {
        expectJobs(1, 5, job(1, "success"), job(2, "skipped"), job(3, "cancelled"),
                job(4, "neutral"), job(5, null));
        mvc.perform(post(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedJobs").isEmpty());
    }

    @Test
    void handlesRunsWithNoJobs() throws Exception {
        expectJobs(1, 0);
        mvc.perform(post(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.failedJobs").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void reportsUnavailableLogsAndContinuesAnalyzingOtherJobs(int logStatus) throws Exception {
        expectJobs(1, 2, job(7, "failure"), job(8, "failure"));
        expectJob(7, "failure");
        github.api.expect(requestTo(ROOT + "/jobs/7/logs"))
                .andRespond(withStatus(HttpStatus.valueOf(logStatus)));
        expectLogs(8, "failure", COMPILER_ERROR);
        mvc.perform(post(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedJobs.length()").value(2))
                .andExpect(jsonPath("$.failedJobs[0].logStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.failedJobs[0].logMessage").value("GitHub job logs are unavailable or expired"))
                .andExpect(jsonPath("$.failedJobs[0].analysis").isEmpty())
                .andExpect(jsonPath("$.failedJobs[1].analysis.category").value("JAVA_COMPILATION"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t"})
    void doesNotInventAnalysisOrFingerprintsForEmptyLogs(String logs) throws Exception {
        expectJobs(1, 1, job(7, "failure"));
        expectLogs(7, "failure", logs);
        mvc.perform(post(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedJobs[0].logStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.failedJobs[0].logMessage").isNotEmpty())
                .andExpect(jsonPath("$.failedJobs[0].analysis").isEmpty());
    }

    @Test
    void reportsExpiredSignedDownloadsAsUnavailable() throws Exception {
        expectJobs(1, 1, job(7, "failure"));
        expectJob(7, "failure");
        github.api.expect(requestTo(ROOT + "/jobs/7/logs"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, DOWNLOAD));
        github.downloads.expect(requestTo(DOWNLOAD)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        mvc.perform(post(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedJobs[0].logStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.failedJobs[0].analysis").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/github/repositories/invalid_owner/demo/runs/42/analyze",
            "/api/github/repositories/octo/invalid!repo/runs/42/analyze",
            "/api/github/repositories/octo/demo/runs/0/analyze",
            "/api/github/repositories/octo/demo/runs/-1/analyze",
            "/api/github/repositories/octo/demo/runs/not-a-number/analyze"
    })
    void rejectsInvalidRepositoriesAndRunIdsWithoutGitHubRequests(String path) throws Exception {
        mvc.perform(post(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500})
    void preservesGitHubErrorsInsteadOfReturningEmptySuccess(int upstreamStatus) throws Exception {
        expectRun();
        github.api.expect(requestTo(ROOT + "/runs/42/attempts/1/jobs?page=1&per_page=100"))
                .andRespond(withStatus(HttpStatus.valueOf(upstreamStatus)).body("private upstream content"));
        int expected = upstreamStatus == 500 ? 502 : upstreamStatus;
        mvc.perform(post(PATH))
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.path").value(PATH))
                .andExpect(jsonPath("$.failedJobs").doesNotExist());
    }

    @Test
    void propagatesLogRateLimitsWithRetryAfterRatherThanMarkingLogsMissing() throws Exception {
        expectJobs(1, 1, job(7, "failure"));
        expectJob(7, "failure");
        github.api.expect(requestTo(ROOT + "/jobs/7/logs"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "120"));
        mvc.perform(post(PATH))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(HttpHeaders.RETRY_AFTER, "120"))
                .andExpect(jsonPath("$.failedJobs").doesNotExist());
    }

    @Test
    void surfacesLogNetworkFailures() throws Exception {
        expectJobs(1, 1, job(7, "failure"));
        expectJob(7, "failure");
        github.api.expect(requestTo(ROOT + "/jobs/7/logs"))
                .andRespond(withException(new IOException("sensitive transport details")));
        mvc.perform(post(PATH)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.status").value(502));
    }

    @Test
    void rejectsIncompletePaginationInsteadOfReturningPartialAnalysis() throws Exception {
        expectJobs(1, 1);
        mvc.perform(post(PATH)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.status").value(502));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{\"id\":0,\"run_id\":42}", "{\"id\":7,\"run_id\":99}"})
    void rejectsInvalidJobs(String job) throws Exception {
        expectJobs(1, 1, job);
        mvc.perform(post(PATH)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.status").value(502));
    }

    @Test
    void rejectsRunsThatAreStillInProgressWithoutClaimingOrDownloadingJobs() throws Exception {
        github.api.expect(requestTo(ROOT + "/runs/42"))
                .andRespond(withSuccess("""
                        {"id":42,"workflow_id":3,"run_number":8,"run_attempt":1,"status":"in_progress"}
                        """, MediaType.APPLICATION_JSON));
        mvc.perform(post(PATH)).andExpect(status().isConflict());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void returnsConflictWhenAnotherWorkerOwnsTheClaim() throws Exception {
        expectRun();
        when(repository.claim(anyString(), any())).thenThrow(new AnalysisConflictException());
        mvc.perform(post(PATH)).andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(HttpHeaders.RETRY_AFTER, "30"));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).fail(any());
    }

    @Test
    void returnsCompletedStoredAnalysisWithoutDownloadingLogsOrRewritingIt() throws Exception {
        expectRun();
        var persisted = new WorkflowRunAnalysis("octo/demo", 42, java.util.List.of());
        when(repository.claim(anyString(), any(GitHubWorkflowRun.class))).thenAnswer(invocation ->
                new WorkflowAnalysisRepository.Claim(new StoredWorkflowAnalysis(
                        "octo/demo", invocation.getArgument(1), AnalysisStatus.COMPLETED,
                        persisted, Instant.now(), Instant.now(), null), null));
        mvc.perform(post(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(42))
                .andExpect(jsonPath("$.failedJobs").isEmpty());
        verify(repository, never()).complete(any(), any());
        verify(repository, never()).renew(any());
        verify(repository, never()).fail(any());
        org.mockito.Mockito.verifyNoInteractions(logStorage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{\"id\":0,\"private\":true}", "{\"id\":-1,\"private\":true}"})
    void rejectsInvalidRepositoryIdsBeforeClaimingOrUploading(String repositoryJson) throws Exception {
        github.api.expect(requestTo(ROOT + "/runs/42"))
                .andRespond(withSuccess("""
                        {"id":42,"workflow_id":3,"run_number":8,"run_attempt":1,
                        "status":"completed","repository":%s}
                        """.formatted(repositoryJson), MediaType.APPLICATION_JSON));
        mvc.perform(post(PATH)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("GitHub returned invalid repository metadata"));
        org.mockito.Mockito.verifyNoInteractions(repository, logStorage);
    }

    private void expectJobs(int page, int total, String... jobs) {
        if (page == 1) {
            expectRun();
        }
        github.api.expect(requestTo(ROOT + "/runs/42/attempts/1/jobs?page=" + page + "&per_page=100"))
                .andRespond(withSuccess("{\"total_count\":" + total + ",\"jobs\":["
                        + String.join(",", jobs) + "]}", MediaType.APPLICATION_JSON));
    }

    private void expectRun() {
        github.api.expect(requestTo(ROOT + "/runs/42"))
                .andRespond(withSuccess("""
                        {"id":42,"workflow_id":3,"run_number":8,"run_attempt":1,
                        "status":"completed","conclusion":"failure",
                        "created_at":"2026-10-17T03:00:00Z",
                        "repository":{"id":1,"full_name":"octo/demo","private":true}}
                        """, MediaType.APPLICATION_JSON));
    }

    private String job(long id, String conclusion) {
        return """
                {"id":%d,"run_id":42,"name":"job-%d","status":"completed","conclusion":%s,
                "steps":[{"name":"Build","number":1}]}
                """.formatted(id, id, conclusion == null ? "null" : "\"" + conclusion + "\"");
    }

    private void expectJob(long id, String conclusion) {
        github.api.expect(requestTo(ROOT + "/jobs/" + id))
                .andRespond(withSuccess(job(id, conclusion), MediaType.APPLICATION_JSON));
    }

    private void expectLogs(long id, String conclusion, String logs) {
        expectJob(id, conclusion);
        github.api.expect(requestTo(ROOT + "/jobs/" + id + "/logs"))
                .andRespond(withSuccess(logs, MediaType.TEXT_PLAIN));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MockGitHubConfiguration {
        private MockRestServiceServer api;
        private MockRestServiceServer downloads;

        @Bean
        @Primary
        GitHubService mockedHttpGitHubService() {
            GitHubConfiguration configuration = new GitHubConfiguration();
            RestClient.Builder apiBuilder = configuration.githubApiClient("https://api.github.com").mutate();
            RestClient.Builder downloadBuilder = configuration.githubLogClient().mutate();
            api = MockRestServiceServer.bindTo(apiBuilder).build();
            downloads = MockRestServiceServer.bindTo(downloadBuilder).build();
            return new GitHubService(apiBuilder.build(), downloadBuilder.build(), "integration-test-token");
        }
    }
}
