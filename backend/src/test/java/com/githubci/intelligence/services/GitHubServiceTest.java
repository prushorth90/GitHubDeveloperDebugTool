package com.githubci.intelligence.services;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import com.githubci.intelligence.configuration.GitHubConfiguration;
import com.githubci.intelligence.dto.GitHubJobs;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.GitHubWorkflowRuns;
import com.githubci.intelligence.exceptions.GitHubException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubServiceTest {

    private static final String ROOT = "https://api.github.com/repos/octo/demo";
    private static final String DOWNLOAD = "https://productionresultssa.blob.core.windows.net/logs/job.txt?sig=secret";
    private static final String FAILED_JOB = """
            {"id":7,"run_id":42,"name":"test","status":"completed","conclusion":"failure",
             "started_at":"2026-10-17T03:00:00Z","completed_at":"2026-10-17T03:02:00Z",
             "steps":[{"name":"Run tests","number":1,"status":"completed","conclusion":"failure",
             "started_at":null,"completed_at":null}],"unknown_field":"ignored"}
            """;

    private MockRestServiceServer apiServer;
    private MockRestServiceServer downloadServer;
    private RestClient api;
    private RestClient downloads;
    private GitHubService service;

    @BeforeEach
    void setUp() {
        GitHubConfiguration configuration = new GitHubConfiguration();
        RestClient.Builder apiBuilder = configuration.githubApiClient("https://api.github.com").mutate();
        RestClient.Builder downloadBuilder = configuration.githubLogClient().mutate();
        apiServer = MockRestServiceServer.bindTo(apiBuilder).build();
        downloadServer = MockRestServiceServer.bindTo(downloadBuilder).build();
        api = apiBuilder.build();
        downloads = downloadBuilder.build();
        service = new GitHubService(api, downloads, "unit-test-token");
    }

    @AfterEach
    void verifyRequests() {
        apiServer.verify();
        downloadServer.verify();
    }

    @Test
    void rejectsWorkflowRunsMissingRequiredIdentifiers() {
        apiServer.expect(requestTo(ROOT + "/actions/runs/42"))
                .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
        assertFailure(HttpStatus.BAD_GATEWAY, () -> service.getWorkflowRun("octo", "demo", 42));
    }

    @Test
    void listsWorkflowRunPagesWithAuthenticationAndOfficialApiHeaders() {
        apiServer.expect(requestTo(ROOT + "/actions/runs?page=1&per_page=1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer unit-test-token"))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header(HttpHeaders.USER_AGENT, "github-ci-intelligence"))
                .andRespond(withSuccess("""
                        {"total_count":2,"workflow_runs":[{"id":42,"workflow_id":3,"run_number":8,
                        "run_attempt":1,"name":"CI","status":"completed","conclusion":"failure",
                        "head_branch":"main","head_sha":"abc","created_at":"2026-10-17T03:00:00Z",
                        "repository":{"id":1,"name":"demo","full_name":"octo/demo","private":true},
                        "unknown_field":true}]}
                        """, MediaType.APPLICATION_JSON));
        apiServer.expect(requestTo(ROOT + "/actions/runs?page=2&per_page=1"))
                .andRespond(withSuccess("""
                        {"total_count":2,"workflow_runs":[{"id":43,"workflow_id":3,"run_number":9,
                        "status":"queued","conclusion":null}]}
                        """, MediaType.APPLICATION_JSON));
        GitHubWorkflowRuns first = service.listWorkflowRuns("octo", "demo", 1, 1);
        GitHubWorkflowRuns second = service.listWorkflowRuns("octo", "demo", 2, 1);
        assertEquals(2, first.totalCount());
        GitHubWorkflowRun run = first.workflowRuns().getFirst();
        assertEquals(42, run.id());
        assertEquals(3, run.workflowId());
        assertEquals(Instant.parse("2026-10-17T03:00:00Z"), run.createdAt());
        assertEquals("octo/demo", run.repository().fullName());
        assertEquals(true, run.repository().privateRepository());
        assertEquals(43, second.workflowRuns().getFirst().id());
        assertNull(second.workflowRuns().getFirst().conclusion());
        assertNull(second.workflowRuns().getFirst().runAttempt());
    }

    @Test
    void retrievesExactRunAttemptInsteadOfLatestRerun() {
        apiServer.expect(requestTo(ROOT + "/actions/runs/42/attempts/2"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer unit-test-token"))
                .andRespond(withSuccess("""
                        {"id":42,"workflow_id":3,"run_number":8,"run_attempt":2,
                        "status":"completed","conclusion":"failure"}
                        """, MediaType.APPLICATION_JSON));
        assertEquals(2, service.getWorkflowRunAttempt("octo", "demo", 42, 2).runAttempt());
    }

    @Test
    void retrievesWorkflowDefinitionAndIndividualRun() {
        apiServer.expect(requestTo(ROOT + "/actions/workflows/3"))
                .andRespond(withSuccess("""
                        {"id":3,"name":"CI","path":".github/workflows/ci.yml","state":"active"}
                        """, MediaType.APPLICATION_JSON));
        apiServer.expect(requestTo(ROOT + "/actions/runs/42"))
                .andRespond(withSuccess("""
                        {"id":42,"workflow_id":3,"run_number":8,"status":"in_progress","conclusion":null}
                        """, MediaType.APPLICATION_JSON));
        assertEquals(".github/workflows/ci.yml", service.getWorkflowDetails("octo", "demo", 3).path());
        assertNull(service.getWorkflowRun("octo", "demo", 42).conclusion());
    }

    @Test
    void listsJobsAndStepsIncludingNullableTimestamps() {
        apiServer.expect(requestTo(ROOT + "/actions/runs/42/jobs?page=2&per_page=100"))
                .andRespond(withSuccess("{\"total_count\":101,\"jobs\":[" + FAILED_JOB + "]}",
                        MediaType.APPLICATION_JSON));
        GitHubJobs result = service.listJobs("octo", "demo", 42, 2, 100);
        assertEquals(101, result.totalCount());
        assertEquals(42, result.jobs().getFirst().runId());
        assertEquals("Run tests", result.jobs().getFirst().steps().getFirst().name());
        assertNull(result.jobs().getFirst().steps().getFirst().startedAt());
    }

    @Test
    void returnsAnEmptyPageWithoutInventingResults() {
        expectRuns(withSuccess("{\"total_count\":0,\"workflow_runs\":[]}", MediaType.APPLICATION_JSON));
        assertEquals(List.of(), service.listWorkflowRuns("octo", "demo", 1, 30).workflowRuns());
    }

    @Test
    void followsLogRedirectsWithoutForwardingAuthorization() {
        expectFailedJob();
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7/logs"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer unit-test-token"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, DOWNLOAD));
        downloadServer.expect(requestTo(DOWNLOAD))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withStatus(HttpStatus.TEMPORARY_REDIRECT)
                        .header(HttpHeaders.LOCATION, "https://logs.actions.githubusercontent.com/job.txt"));
        downloadServer.expect(requestTo("https://logs.actions.githubusercontent.com/job.txt"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("step failed\n", MediaType.TEXT_PLAIN));
        assertEquals("step failed\n", service.getFailedJobLogs("octo", "demo", 7));
    }

    @Test
    void supportsDirectLogResponsesAndTimedOutJobs() {
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7"))
                .andRespond(withSuccess(FAILED_JOB.replace("\"failure\"", "\"timed_out\""),
                        MediaType.APPLICATION_JSON));
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7/logs"))
                .andRespond(withSuccess("timed out", MediaType.TEXT_PLAIN));
        assertEquals("timed out", service.getFailedJobLogs("octo", "demo", 7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "cancelled", "skipped", "neutral"})
    void doesNotDownloadLogsForNonFailedJobs(String conclusion) {
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7"))
                .andRespond(withSuccess(FAILED_JOB.replace("\"failure\"", "\"" + conclusion + "\""),
                        MediaType.APPLICATION_JSON));
        assertFailure(HttpStatus.CONFLICT, () -> service.getFailedJobLogs("octo", "demo", 7));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://productionresultssa.blob.core.windows.net/log.txt",
            "https://evil.example/log.txt",
            "https://api.github.com.evil.example/log.txt",
            "https://127.0.0.1/log.txt",
            "https://169.254.169.254/latest/meta-data",
            "https://user:password@productionresultssa.blob.core.windows.net/log.txt",
            "https://productionresultssa.blob.core.windows.net:8443/log.txt",
            "https://productionresultssa.blob.core.windows.net/log.txt#fragment",
            "https://productionresultssa.blob.core.windows.net.evil.example/log.txt",
            "/relative/path",
            "not a URI"})
    void rejectsUnsafeDownloadLocationsBeforeSendingAnyRequest(String location) {
        expectFailedJob();
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7/logs"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location));
        assertFailure(HttpStatus.BAD_GATEWAY, () -> service.getFailedJobLogs("octo", "demo", 7));
    }

    @Test
    void validatesEveryRedirectIncludingLaterHops() {
        expectLogRedirect();
        downloadServer.expect(requestTo(DOWNLOAD))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "http://localhost/secrets"));
        assertFailure(HttpStatus.BAD_GATEWAY, () -> service.getFailedJobLogs("octo", "demo", 7));
    }

    @Test
    void boundsRedirectChains() {
        expectLogRedirect();
        for (int i = 0; i < 5; i++) {
            downloadServer.expect(requestTo(DOWNLOAD))
                    .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, DOWNLOAD));
        }
        assertFailure(HttpStatus.BAD_GATEWAY, () -> service.getFailedJobLogs("octo", "demo", 7));
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410, 204})
    void handlesExpiredDeletedAndEmptyApiLogs(int status) {
        expectFailedJob();
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7/logs"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));
        assertFailure(HttpStatus.GONE, () -> service.getFailedJobLogs("octo", "demo", 7));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 410})
    void handlesExpiredSignedDownloads(int status) {
        expectLogRedirect();
        downloadServer.expect(requestTo(DOWNLOAD)).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertFailure(HttpStatus.GONE, () -> service.getFailedJobLogs("octo", "demo", 7));
    }

    @Test
    void rejectsLogsLargerThanTenMiB() {
        expectFailedJob();
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7/logs"))
                .andRespond(withSuccess(new byte[10 * 1024 * 1024 + 1], MediaType.TEXT_PLAIN));
        assertFailure(HttpStatus.BAD_GATEWAY, () -> service.getFailedJobLogs("octo", "demo", 7));
    }

    @Test
    void acceptsLogsAtExactlyTenMiB() {
        expectFailedJob();
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7/logs"))
                .andRespond(withSuccess(new byte[10 * 1024 * 1024], MediaType.TEXT_PLAIN));
        assertEquals(10 * 1024 * 1024, service.getFailedJobLogs("octo", "demo", 7).length());
    }

    @Test
    void rejectsRedirectsWithoutALocation() {
        expectFailedJob();
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7/logs")).andRespond(withStatus(HttpStatus.FOUND));
        assertFailure(HttpStatus.BAD_GATEWAY, () -> service.getFailedJobLogs("octo", "demo", 7));
    }

    @ParameterizedTest
    @CsvSource({"401,401", "403,403", "404,404", "500,502", "503,502", "302,502"})
    void translatesUpstreamErrorsWithoutExposingUpstreamBodies(int upstream, int expected) {
        expectRuns(withStatus(HttpStatus.valueOf(upstream))
                .body("sensitive upstream body unit-test-token"));
        GitHubException exception = assertFailure(HttpStatus.valueOf(expected),
                () -> service.listWorkflowRuns("octo", "demo", 1, 30));
        assertFalse(exception.getMessage().contains("sensitive"));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 429})
    void surfacesRateLimitsWithRetryAfterWithoutAutomaticRetries(int status) {
        expectRuns(withStatus(HttpStatus.valueOf(status)).header(HttpHeaders.RETRY_AFTER, "90"));
        GitHubException exception = assertFailure(HttpStatus.TOO_MANY_REQUESTS,
                () -> service.listWorkflowRuns("octo", "demo", 1, 30));
        assertEquals("90", exception.getRetryAfter());
    }

    @Test
    void derivesRetryDelayFromPrimaryRateLimitReset() {
        expectRuns(withStatus(HttpStatus.FORBIDDEN).header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", Long.toString(Instant.now().getEpochSecond() + 120)));
        GitHubException exception = assertFailure(HttpStatus.TOO_MANY_REQUESTS,
                () -> service.listWorkflowRuns("octo", "demo", 1, 30));
        long delay = Long.parseLong(exception.getRetryAfter());
        org.junit.jupiter.api.Assertions.assertTrue(delay > 0 && delay <= 120);
    }

    @Test
    void detectsSecondaryRateLimitsWithoutRetryHeaders() {
        expectRuns(withStatus(HttpStatus.FORBIDDEN).body("{\"message\":\"You have exceeded a secondary rate limit.\"}"));
        GitHubException exception = assertFailure(HttpStatus.TOO_MANY_REQUESTS,
                () -> service.listWorkflowRuns("octo", "demo", 1, 30));
        assertEquals("60", exception.getRetryAfter());
    }

    @Test
    void usesSafeRetryDelayForMalformedRateMetadata() {
        expectRuns(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "not-a-number"));
        GitHubException exception = assertFailure(HttpStatus.TOO_MANY_REQUESTS,
                () -> service.listWorkflowRuns("octo", "demo", 1, 30));
        assertEquals("60", exception.getRetryAfter());
    }

    @Test
    void sanitizesNetworkFailures() {
        expectRuns(withException(new IOException("unit-test-token")));
        assertFailure(HttpStatus.BAD_GATEWAY, () -> service.listWorkflowRuns("octo", "demo", 1, 30));
    }

    @Test
    void sanitizesDownloadFailuresIncludingSignedUrls() {
        expectLogRedirect();
        downloadServer.expect(requestTo(DOWNLOAD)).andRespond(withException(new IOException(DOWNLOAD)));
        GitHubException exception = assertFailure(HttpStatus.BAD_GATEWAY,
                () -> service.getFailedJobLogs("octo", "demo", 7));
        assertFalse(exception.getMessage().contains("sig="));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{not-json", "{}", "{\"total_count\":-1,\"workflow_runs\":[]}"})
    void handlesEmptyMalformedOrInvalidResponses(String body) {
        expectRuns(withSuccess(body, MediaType.APPLICATION_JSON));
        assertFailure(HttpStatus.BAD_GATEWAY, () -> service.listWorkflowRuns("octo", "demo", 1, 30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "token\ninjected"})
    void rejectsMissingOrInvalidTokensWithoutSendingARequest(String token) {
        GitHubService unconfigured = new GitHubService(api, downloads, token);
        assertFailure(HttpStatus.SERVICE_UNAVAILABLE, () -> unconfigured.listWorkflowRuns("octo", "demo", 1, 30));
    }

    @Test
    void validatesInputsBeforeCallingGitHub() {
        assertFailure(HttpStatus.BAD_REQUEST, () -> service.listWorkflowRuns("octo", "demo", 0, 30));
        assertFailure(HttpStatus.BAD_REQUEST, () -> service.listWorkflowRuns("octo", "demo", 1, 0));
        assertFailure(HttpStatus.BAD_REQUEST, () -> service.listWorkflowRuns("octo", "demo", 1, 101));
        assertFailure(HttpStatus.BAD_REQUEST, () -> service.listWorkflowRuns("bad/owner", "demo", 1, 30));
        assertFailure(HttpStatus.BAD_REQUEST, () -> service.listWorkflowRuns("octo", "..", 1, 30));
        assertFailure(HttpStatus.BAD_REQUEST, () -> service.listJobs("octo", "demo", -1, 1, 30));
        assertFailure(HttpStatus.BAD_REQUEST, () -> service.getWorkflowDetails("octo", "demo", 0));
        assertFailure(HttpStatus.BAD_REQUEST, () -> service.getWorkflowRun("octo", "demo", 0));
        assertFailure(HttpStatus.BAD_REQUEST, () -> service.getFailedJobLogs("octo", "demo", 0));
    }

    @Test
    void refusesToSendTokensToAnAlternateApiHost() {
        assertThrows(IllegalArgumentException.class,
                () -> new GitHubConfiguration().githubApiClient("https://untrusted.example"));
    }

    private void expectRuns(org.springframework.test.web.client.ResponseCreator response) {
        apiServer.expect(requestTo(ROOT + "/actions/runs?page=1&per_page=30")).andRespond(response);
    }

    private void expectFailedJob() {
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7"))
                .andRespond(withSuccess(FAILED_JOB, MediaType.APPLICATION_JSON));
    }

    private void expectLogRedirect() {
        expectFailedJob();
        apiServer.expect(requestTo(ROOT + "/actions/jobs/7/logs"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, DOWNLOAD));
    }

    private GitHubException assertFailure(HttpStatus status, org.junit.jupiter.api.function.Executable action) {
        GitHubException exception = assertThrows(GitHubException.class, action);
        assertEquals(status, exception.getStatus());
        assertFalse(exception.getMessage().contains("unit-test-token"));
        assertNull(exception.getCause());
        assertNotNull(exception.getMessage());
        return exception;
    }
}
