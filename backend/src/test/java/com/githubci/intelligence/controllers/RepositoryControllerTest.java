package com.githubci.intelligence.controllers;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.githubci.intelligence.dto.FailureResponses.*;
import com.githubci.intelligence.exceptions.FailureReadException;
import com.githubci.intelligence.exceptions.GlobalExceptionHandler;
import com.githubci.intelligence.services.FailureService;
import com.githubci.intelligence.services.FailureService.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.githubci.intelligence.dto.FailureCategory.JAVA_COMPILATION;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RepositoryControllerTest {
    private FailureService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FailureService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RepositoryController(service))
                .setControllerAdvice(new GlobalExceptionHandler(Clock.systemUTC())).build();
    }

    @Test
    void listsRepositoryDtosInStandardEnvelope() throws Exception {
        when(service.repositories(20, null)).thenReturn(new Page<>(
                List.of(new Repository(1, "demo", "octo/demo", null, true)), null));
        mvc.perform(get("/api/repositories")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(1))
                .andExpect(jsonPath("$.data.items[0].fullName").value("octo/demo"))
                .andExpect(jsonPath("$.data.items[0].privateRepository").value(true));
    }

    @Test
    void forwardsRunFiltersPaginationAndOrder() throws Exception {
        when(service.runs(1, false, JAVA_COMPILATION, Order.asc, 10, "cursor"))
                .thenReturn(new Page<>(List.of(), "next"));
        mvc.perform(get("/api/repositories/1/runs").param("category", "JAVA_COMPILATION")
                        .param("order", "asc").param("limit", "10").param("nextToken", "cursor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.nextToken").value("next"));
        verify(service).runs(1, false, JAVA_COMPILATION, Order.asc, 10, "cursor");
    }

    @Test
    void listsFailuresWithDescendingDefaults() throws Exception {
        when(service.runs(1, true, null, Order.desc, 20, null)).thenReturn(new Page<>(List.of(), null));
        mvc.perform(get("/api/repositories/1/failures")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        verify(service).runs(1, true, null, Order.desc, 20, null);
    }

    @Test
    void retrievesFailureDetailsForExplicitAttempt() throws Exception {
        when(service.failure(1, 42, 2)).thenReturn(new Failure(null, List.of()));
        mvc.perform(get("/api/repositories/1/failures/42").param("attempt", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.failedJobs").isArray());
        verify(service).failure(1, 42, 2);
    }

    @Test
    void retrievesStatisticsInStandardEnvelope() throws Exception {
        when(service.statistics(1)).thenReturn(new Statistics(2, 1, 1, 0, 1, 0, Map.of(JAVA_COMPILATION, 1L), null, null));
        mvc.perform(get("/api/repositories/1/statistics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRuns").value(2))
                .andExpect(jsonPath("$.data.latestRun").isEmpty())
                .andExpect(jsonPath("$.data.lastAnalyzedAt").isEmpty())
                .andExpect(jsonPath("$.data.failuresByCategory.JAVA_COMPILATION").value(1));
    }

    @Test
    void retrievesSimilarFailuresWithAllSelectors() throws Exception {
        when(service.similar(1, 42, 2, JAVA_COMPILATION, Order.asc, 5, "cursor"))
                .thenReturn(new Page<>(List.of(), null));
        mvc.perform(get("/api/repositories/1/failures/42/similar").param("attempt", "2")
                        .param("category", "JAVA_COMPILATION").param("order", "asc")
                        .param("limit", "5").param("nextToken", "cursor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        verify(service).similar(1, 42, 2, JAVA_COMPILATION, Order.asc, 5, "cursor");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/repositories?limit=text", "/api/repositories/x/runs",
            "/api/repositories/1/failures?category=invalid", "/api/repositories/1/runs?order=invalid",
            "/api/repositories/1/failures/x", "/api/repositories/1/failures/42?attempt=text",
            "/api/repositories/1/failures/42/similar?limit=text", "/api/repositories/x/statistics"})
    void rejectsBindingErrorsUsingStandardErrorResponse(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.path").isString())
                .andExpect(jsonPath("$.timestamp").exists());
        verifyNoInteractions(service);
    }

    @Test
    void returnsNotFoundConflictAndDatabaseFailuresWithoutInternalDetails() throws Exception {
        when(service.failure(1, 42, null)).thenThrow(new FailureReadException(HttpStatus.NOT_FOUND, "Workflow run not found"));
        mvc.perform(get("/api/repositories/1/failures/42")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Workflow run not found"));
        doThrow(new FailureReadException(HttpStatus.CONFLICT, "Workflow analysis is still processing"))
                .when(service).failure(1, 42, null);
        mvc.perform(get("/api/repositories/1/failures/42")).andExpect(status().isConflict());
        when(service.statistics(1)).thenThrow(new FailureReadException(HttpStatus.SERVICE_UNAVAILABLE,
                "Analysis database is temporarily unavailable", new IllegalStateException("secret")));
        mvc.perform(get("/api/repositories/1/statistics")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.message").value("Analysis database is temporarily unavailable"))
                .andExpect(jsonPath("$.cause").doesNotExist());
    }
}
