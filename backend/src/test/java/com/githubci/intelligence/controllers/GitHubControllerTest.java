package com.githubci.intelligence.controllers;

import java.io.IOException;
import java.time.Clock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.githubci.intelligence.configuration.GitHubConfiguration;
import com.githubci.intelligence.exceptions.GlobalExceptionHandler;
import com.githubci.intelligence.services.GitHubService;
import com.githubci.intelligence.services.FailureAnalyzerService;
import com.githubci.intelligence.services.WorkflowRunAnalysisService;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GitHubControllerTest {

    private static final String PATH = "/api/github/repositories/octo/demo";
    private MockRestServiceServer server;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = new GitHubConfiguration().githubApiClient("https://api.github.com").mutate();
        server = MockRestServiceServer.bindTo(builder).build();
        GitHubService service = new GitHubService(builder.build(), RestClient.create(), "unit-test-token");
        mvc = MockMvcBuilders.standaloneSetup(new GitHubController(service,
                        new WorkflowRunAnalysisService(service, new FailureAnalyzerService(),
                                mock(WorkflowAnalysisRepository.class))))
                .setControllerAdvice(new GlobalExceptionHandler(Clock.systemUTC())).build();
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void returnsWorkflowRunEnvelopeWithDefaultPagination() throws Exception {
        server.expect(requestTo("https://api.github.com/repos/octo/demo/actions/runs?page=1&per_page=30"))
                .andRespond(withSuccess("""
                        {"total_count":1,"workflow_runs":[{"id":42,"workflow_id":3,"run_number":8,
                        "name":"CI","status":"completed",
                        "conclusion":"failure","repository":{"id":1,"full_name":"octo/demo","private":true}}]}
                        """, MediaType.APPLICATION_JSON));
        mvc.perform(get(PATH + "/workflows"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.total_count").value(1))
                .andExpect(jsonPath("$.workflow_runs[0].id").value(42))
                .andExpect(jsonPath("$.workflow_runs[0].repository.full_name").value("octo/demo"))
                .andExpect(jsonPath("$.workflow_runs[0].repository.private").value(true));
    }

    @Test
    void returnsJobsAndPassesPaginationToGitHub() throws Exception {
        server.expect(requestTo("https://api.github.com/repos/octo/demo/actions/runs/42/jobs?page=2&per_page=100"))
                .andRespond(withSuccess("""
                        {"total_count":101,"jobs":[{"id":7,"run_id":42,"name":"test","conclusion":"failure",
                        "steps":[{"name":"Run tests","number":1,"conclusion":"failure"}]}]}
                        """, MediaType.APPLICATION_JSON));
        mvc.perform(get(PATH + "/runs/42/jobs").param("page", "2").param("per_page", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_count").value(101))
                .andExpect(jsonPath("$.jobs[0].run_id").value(42))
                .andExpect(jsonPath("$.jobs[0].steps[0].name").value("Run tests"));
    }

    @Test
    void rejectsInvalidPaginationAndRunIdsWithoutUpstreamCalls() throws Exception {
        mvc.perform(get(PATH + "/workflows").param("page", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/workflows").param("per_page", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/runs/0/jobs")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/runs/not-a-number/jobs")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/workflows").param("page", "invalid")).andExpect(status().isBadRequest());
    }

    @Test
    void returnsSafeAuthenticationErrorsUsingExistingErrorContract() throws Exception {
        server.expect(requestTo("https://api.github.com/repos/octo/demo/actions/runs?page=1&per_page=30"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("unit-test-token must never appear"));
        mvc.perform(get(PATH + "/workflows"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("GitHub authentication failed; check the server token"))
                .andExpect(jsonPath("$.path").value(PATH + "/workflows"));
    }

    @Test
    void propagatesRateLimitRetryAfter() throws Exception {
        server.expect(requestTo("https://api.github.com/repos/octo/demo/actions/runs?page=1&per_page=30"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "120"));
        mvc.perform(get(PATH + "/workflows"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "120"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void surfacesNetworkFailuresInsteadOfReturningEmptyResults() throws Exception {
        server.expect(requestTo("https://api.github.com/repos/octo/demo/actions/runs?page=1&per_page=30"))
                .andRespond(withException(new IOException("sensitive upstream details")));
        mvc.perform(get(PATH + "/workflows"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.workflow_runs").doesNotExist());
    }

    @Test
    void logsOnlySanitizedErrorsWithoutTokensBodiesOrTransportDetails() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            server.expect(requestTo("https://api.github.com/repos/octo/demo/actions/runs?page=1&per_page=30"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("unit-test-token sensitive-body"));
            server.expect(requestTo("https://api.github.com/repos/octo/demo/actions/runs?page=1&per_page=30"))
                    .andRespond(withException(new IOException("unit-test-token sig=sensitive-body")));
            mvc.perform(get(PATH + "/workflows")).andExpect(status().isUnauthorized());
            mvc.perform(get(PATH + "/workflows")).andExpect(status().isBadGateway());
            org.junit.jupiter.api.Assertions.assertEquals(2, appender.list.size());
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("unit-test-token"));
                assertFalse(event.getFormattedMessage().contains("sensitive-body"));
                assertFalse(event.getFormattedMessage().contains("sig="));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
