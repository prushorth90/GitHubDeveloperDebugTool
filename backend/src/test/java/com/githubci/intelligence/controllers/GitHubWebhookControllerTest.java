package com.githubci.intelligence.controllers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.exceptions.GlobalExceptionHandler;
import com.githubci.intelligence.services.GitHubWebhookService;
import com.githubci.intelligence.queue.WorkflowEventPublisher;
import com.githubci.intelligence.exceptions.WebhookException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"github.webhook-secret=unit-test-webhook-secret", "github.token="})
class GitHubWebhookControllerTest {

    private static final String SECRET = "unit-test-webhook-secret";
    private static final String PATH = "/api/webhooks/github";
    private static final String PAYLOAD = """
            {
              "action": "completed",
              "repository": {"id": 123, "full_name": "octo/demo"},
              "installation": {"id": 456},
              "workflow_run": {"id": 789, "run_attempt": 2, "status": "completed", "conclusion": "failure"},
              "ignored": "private-payload-marker"
            }
            """;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper mapper;
    @MockitoBean
    private WorkflowEventPublisher publisher;

    private MockMvc mvc;
    private String delivery;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        delivery = UUID.randomUUID().toString();
        logger = (Logger) LoggerFactory.getLogger(GitHubWebhookService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void acceptsValidSignatureAndLogsOnlyExtractedMetadata() throws Exception {
        mvc.perform(signed(PAYLOAD, "workflow_run", delivery))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertEquals(1, appender.list.size());
        assertEquals(java.util.List.of(123L, "octo/demo", 456L, 789L, 2, "failure", delivery),
                java.util.Arrays.asList(appender.list.getFirst().getArgumentArray()));
        String message = appender.list.getFirst().getFormattedMessage();
        assertFalse(message.contains("private-payload-marker"));
        assertFalse(message.contains(SECRET));
        assertFalse(message.contains("sha256="));
        assertNull(appender.list.getFirst().getThrowableProxy());
        verify(publisher).publish(new WorkflowEvent(123, "octo/demo", 456L, 789, 2, "failure", delivery));
    }

    @Test
    void validatesOriginalBytesIncludingWhitespaceAndMultibyteUtf8() throws Exception {
        String payload = PAYLOAD.replace("private-payload-marker", "private-\\u00e9")
                .replace("\\u00e9", Character.toString(0x00e9));
        mvc.perform(signed(payload, "workflow_run", delivery)).andExpect(status().isNoContent());
        mvc.perform(request(payload.strip(), "workflow_run", UUID.randomUUID().toString())
                        .header("X-Hub-Signature-256", signature(payload.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized());
        assertEquals(1, appender.list.size());
    }

    @Test
    void rejectsMissingMalformedAndIncorrectSignaturesBeforeParsing() throws Exception {
        for (String signature : new String[] {"", "sha1=" + "a".repeat(40),
                "sha256=not-hex", "sha256=" + "0".repeat(64)}) {
            mvc.perform(request("{invalid-json", "workflow_run", delivery)
                            .header("X-Hub-Signature-256", signature))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid GitHub webhook signature"));
        }
        mvc.perform(request(PAYLOAD, "workflow_run", delivery))
                .andExpect(status().isUnauthorized());
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void alsoRequiresSignaturesForUnrelatedEventsAndDuplicateDeliveries() throws Exception {
        mvc.perform(signed(PAYLOAD, "workflow_run", delivery)).andExpect(status().isNoContent());
        mvc.perform(request(PAYLOAD, "workflow_run", delivery))
                .andExpect(status().isUnauthorized());
        mvc.perform(request("{}", "ping", delivery)).andExpect(status().isUnauthorized());
        assertEquals(1, appender.list.size());
    }

    @Test
    void republishesDuplicateDeliveriesForDurableWorkerDeduplication() throws Exception {
        mvc.perform(signed(PAYLOAD, "workflow_run", delivery)).andExpect(status().isNoContent());
        mvc.perform(signed(PAYLOAD, "workflow_run", delivery.toUpperCase(java.util.Locale.ROOT)))
                .andExpect(status().isNoContent());
        mvc.perform(signed(PAYLOAD.replace("\"run_attempt\": 2", "\"run_attempt\": 3"),
                        "workflow_run", UUID.randomUUID().toString()))
                .andExpect(status().isNoContent());
        assertEquals(3, appender.list.size());
        verify(publisher, times(3)).publish(any());
        assertEquals(3, appender.list.getLast().getArgumentArray()[4]);
    }

    @Test
    void publishesConcurrentDuplicateDeliveries() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = IntStream.range(0, 8)
                    .<java.util.concurrent.Callable<Integer>>mapToObj(index -> () ->
                            mvc.perform(signed(PAYLOAD, "workflow_run", delivery))
                                    .andReturn().getResponse().getStatus())
                    .toList();
            for (var result : executor.invokeAll(tasks)) {
                assertEquals(204, result.get());
            }
        }
        assertEquals(8, appender.list.size());
        verify(publisher, times(8)).publish(any());
    }

    @Test
    void queueFailureReturns503AndRedeliveryCanPublish() throws Exception {
        doThrow(new WebhookException(HttpStatus.SERVICE_UNAVAILABLE, "Could not enqueue workflow event"))
                .doNothing().when(publisher).publish(any());
        mvc.perform(signed(PAYLOAD, "workflow_run", delivery)).andExpect(status().isServiceUnavailable());
        assertTrue(appender.list.isEmpty());
        mvc.perform(signed(PAYLOAD, "workflow_run", delivery)).andExpect(status().isNoContent());
        verify(publisher, times(2)).publish(any());
        assertEquals(1, appender.list.size());
    }

    @Test
    void rejectionLogsAndResponsesDoNotExposePayloadOrSignature() throws Exception {
        Logger errors = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> rejected = new ListAppender<>();
        rejected.start();
        errors.addAppender(rejected);
        try {
            String unsafe = PAYLOAD.replace("\"conclusion\": \"failure\"",
                    "\"conclusion\": \"private-payload-marker\"");
            String signature = signature(unsafe.getBytes(StandardCharsets.UTF_8));
            var response = mvc.perform(signed(unsafe, "workflow_run", delivery))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("private-payload-marker"));
            assertFalse(response.contains(signature));
            assertEquals(1, rejected.list.size());
            var entry = rejected.list.getFirst();
            assertFalse(entry.getFormattedMessage().contains("private-payload-marker"));
            assertFalse(entry.getFormattedMessage().contains(SECRET));
            assertFalse(entry.getFormattedMessage().contains(signature));
            assertNull(entry.getThrowableProxy());
        } finally {
            errors.detachAppender(rejected);
            rejected.stop();
        }
    }

    @Test
    void rejectsMalformedSignedPayloadsAndDoesNotPoisonDeduplication() throws Exception {
        for (String payload : new String[] {"", "{broken", "null", "[]", "{}",
                PAYLOAD + " {}", PAYLOAD.replace("\"id\": 123", "\"id\": \"123\""),
                PAYLOAD.replace("\"id\": 123", "\"id\": 9223372036854775808"),
                PAYLOAD.replace("\"run_attempt\": 2", "\"run_attempt\": 0"),
                PAYLOAD.replace("\"run_attempt\": 2", "\"run_attempt\": 2147483648"),
                PAYLOAD.replace("\"run_attempt\": 2", "\"run_attempt\": 1.5"),
                PAYLOAD.replace("\"id\": 456", "\"id\": -1"),
                PAYLOAD.replace("\"conclusion\": \"failure\"", "\"conclusion\": null"),
                PAYLOAD.replace("\"full_name\": \"octo/demo\"", "\"full_name\": \"octo/demo\\nforged-log\"")}) {
            mvc.perform(signed(payload, "workflow_run", delivery))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Invalid GitHub webhook payload or headers"));
        }
        assertTrue(appender.list.isEmpty());
        mvc.perform(signed(PAYLOAD, "workflow_run", delivery)).andExpect(status().isNoContent());
        assertEquals(1, appender.list.size());
    }

    @Test
    void rejectsMissingOrUnsafeHeadersOnRelevantEvents() throws Exception {
        mvc.perform(signed(PAYLOAD, null, delivery)).andExpect(status().isBadRequest());
        mvc.perform(signed(PAYLOAD, "workflow_run", null)).andExpect(status().isBadRequest());
        mvc.perform(signed(PAYLOAD, "workflow_run", "untrusted delivery"))
                .andExpect(status().isBadRequest());
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void ignoresOtherEventsAndNonCompletedRunsWithoutLogging() throws Exception {
        mvc.perform(signed("{not-parsed", "push", delivery)).andExpect(status().isNoContent());
        mvc.perform(signed("{}", "ping", delivery)).andExpect(status().isNoContent());
        mvc.perform(signed("""
                {"action":"requested"}
                """, "workflow_run", delivery)).andExpect(status().isNoContent());
        mvc.perform(signed(PAYLOAD.replace("\"status\": \"completed\"", "\"status\": \"in_progress\""),
                        "workflow_run", delivery))
                .andExpect(status().isNoContent());
        assertTrue(appender.list.isEmpty());
        mvc.perform(signed(PAYLOAD, "workflow_run", delivery)).andExpect(status().isNoContent());
        assertEquals(1, appender.list.size());
    }

    @Test
    void queuesSuccessfulAndFailedRunsWithOptionalInstallation() throws Exception {
        for (String payload : new String[] {
                PAYLOAD.replace("\"installation\": {\"id\": 456},", ""),
                PAYLOAD.replace("\"installation\": {\"id\": 456}", "\"installation\": null")}) {
            mvc.perform(signed(payload.replace("\"failure\"", "\"success\""),
                            "workflow_run", UUID.randomUUID().toString()))
                    .andExpect(status().isNoContent());
            assertEquals("success", appender.list.getLast().getArgumentArray()[5]);
        }
        mvc.perform(signed(PAYLOAD.replace("\"installation\": {\"id\": 456},", ""),
                "workflow_run", delivery)).andExpect(status().isNoContent());
        assertNull(appender.list.getLast().getArgumentArray()[2]);
        verify(publisher, times(3)).publish(any());
    }

    @Test
    void missingSecretFailsClosedWithoutAffectingOtherRoutes() throws Exception {
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(new GitHubWebhookController(
                        new GitHubWebhookService("", mapper, mock(WorkflowEventPublisher.class))))
                .setControllerAdvice(new GlobalExceptionHandler(Clock.systemUTC())).build();
        unconfigured.perform(signed(PAYLOAD, "workflow_run", delivery))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("GitHub webhook requires GITHUB_WEBHOOK_SECRET"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health"))
                .andExpect(status().isOk());
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void workflowEventHasStableQueueReadyJsonShape() {
        WorkflowEvent event = new WorkflowEvent(123, "octo/demo", null, 789, 2, "failure", delivery);
        var json = mapper.readTree(mapper.writeValueAsString(event));
        assertEquals(7, json.size());
        assertEquals(123, json.path("repository_id").asLong());
        assertEquals("octo/demo", json.path("repository_name").asText());
        assertTrue(json.path("installation_id").isNull());
        assertEquals(789, json.path("workflow_run_id").asLong());
        assertEquals(2, json.path("run_attempt").asInt());
        assertEquals("failure", json.path("conclusion").asText());
        assertEquals(delivery, json.path("delivery_id").asText());
    }

    private MockHttpServletRequestBuilder signed(String payload, String event, String deliveryId) throws Exception {
        return request(payload, event, deliveryId)
                .header("X-Hub-Signature-256", signature(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private MockHttpServletRequestBuilder request(String payload, String event, String deliveryId) {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(payload.getBytes(StandardCharsets.UTF_8));
        if (event != null) {
            request.header("X-GitHub-Event", event);
        }
        if (deliveryId != null) {
            request.header("X-GitHub-Delivery", deliveryId);
        }
        return request;
    }

    private String signature(byte[] bytes) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(bytes));
    }
}
