package com.githubci.intelligence.exceptions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneOffset.UTC);
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler(clock)).build();
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        assertError(mvc.perform(get("/test/failure")), 500, "Internal Server Error",
                "An unexpected error occurred", "/test/failure");
    }

    @Test
    void handlesBeanValidation() throws Exception {
        assertError(mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}")), 400, "Bad Request", "Request validation failed", "/test/input");
    }

    @Test
    void handlesConstraintViolations() throws Exception {
        assertError(mvc.perform(get("/test/constraint")), 400, "Bad Request",
                "Request validation failed", "/test/constraint");
    }

    @Test
    void handlesMalformedJson() throws Exception {
        assertError(mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                .content("{")), 400, "Bad Request", "Bad Request", "/test/input");
    }

    @Test
    void preservesMethodNotAllowedStatusAndHeaders() throws Exception {
        ResultActions result = mvc.perform(post("/test/failure"));
        assertError(result, 405, "Method Not Allowed", "Method Not Allowed", "/test/failure");
        result.andExpect(header().string("Allow", "GET"));
    }

    @Test
    void handlesMissingRoutes() throws Exception {
        assertError(mvc.perform(get("/missing")), 404, "Not Found", "Not Found", "/missing");
    }

    @Test
    void reportsProcessingConflictsWithRetryAdvice() throws Exception {
        ResultActions result = mvc.perform(get("/test/processing"));
        assertError(result, 409, "Conflict",
                "Analysis is already processing or its lease was lost; retry later", "/test/processing");
        result.andExpect(header().string("Retry-After", "30"));
    }

    private void assertError(ResultActions result, int code, String error, String message, String path)
            throws Exception {
        result.andExpect(status().is(code))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.timestamp").value("2026-10-17T03:00:00Z"))
                .andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.error").value(error))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(path));
    }

    @RestController
    static class FailureController {

        @GetMapping("/test/failure")
        public void fail() {
            throw new IllegalStateException("Sensitive internal details");
        }

        @GetMapping("/test/constraint")
        public void constraint() {
            throw new ConstraintViolationException(Set.of());
        }

        @GetMapping("/test/processing")
        public void processing() {
            throw new AnalysisConflictException();
        }

        @PostMapping("/test/input")
        public void input(@Valid @RequestBody Input input) {
        }
    }

    record Input(@NotBlank String name) {
    }
}
