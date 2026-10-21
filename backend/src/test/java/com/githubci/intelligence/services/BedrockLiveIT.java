package com.githubci.intelligence.services;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubJobs;
import com.githubci.intelligence.dto.GitHubRepository;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.queue.SqsWorkflowEventConsumer;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "BEDROCK_LIVE_TEST", matches = "true")
@SpringBootTest(properties = {
        "app.bedrock.enabled=true", "app.bedrock.region=us-west-2",
        "app.bedrock.model-id=us.amazon.nova-lite-v1:0", "app.bedrock.profile=prushorthaws",
        "app.bedrock.include-commit-changes=false", "app.sqs.worker-enabled=false",
        "github.token=", "github.webhook-secret=synthetic-smoke-test-secret"
})
class BedrockLiveIT {
    private static final String QUEUE = "bedrock-smoke-" + UUID.randomUUID();
    private static final long REPOSITORY_ID = 900000000001L;
    private static final String REPOSITORY = "octo/bedrock-smoke";
    private static final String LOGS = """
            ##[group]Run mvn compile
            [ERROR] /src/App.java:[12,4] cannot find symbol
            [ERROR] symbol:   variable missingValue
            [ERROR] BUILD FAILURE
            ##[endgroup]
            Error: Process completed with exit code 1.
            """;

    @DynamicPropertySource
    static void queue(DynamicPropertyRegistry properties) {
        properties.add("app.sqs.queue-url",
                () -> "http://localstack:4566/queue/us-east-1/000000000000/" + QUEUE);
    }

    @Autowired WebApplicationContext context;
    @Autowired SqsClient sqs;
    @Autowired ObjectMapper mapper;
    @Autowired WorkflowEventWorker worker;
    @Autowired WorkflowAnalysisRepository repository;
    @Autowired LogStorageService storage;
    @Autowired LogRedactionService redactor;
    @MockitoBean GitHubService github;
    @MockitoSpyBean BedrockRuntimeClient bedrock;
    @Value("${BEDROCK_LIVE_RESPONSE_FILE}") String responseFile;
    @Value("${app.sqs.queue-url}") String queueUrl;
    @Value("${app.sqs.endpoint}") String sqsEndpoint;
    @Value("${app.s3.endpoint}") String s3Endpoint;
    @Value("${app.dynamodb.endpoint}") String dynamoEndpoint;

    @Test
    void onePaidAnalysisThroughSignedWebhookQueueStorageAndReadApi() throws Exception {
        assertEquals("http://localstack:4566", sqsEndpoint);
        assertEquals("http://localstack:4566", s3Endpoint);
        assertEquals("http://dynamodb-local:8000", dynamoEndpoint);
        Path capture = Path.of(responseFile);
        Files.writeString(capture, "");
        var realClient = (BedrockRuntimeClient) mockingDetails(bedrock).getMockCreationSettings().getSpiedInstance();
        assertNotNull(realClient);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            assertEquals(1, calls.incrementAndGet(), "Refusing a second paid Bedrock invocation");
            ConverseRequest request = invocation.getArgument(0);
            assertTrue(request.system().getFirst().text().contains(
                    "OBSERVATION findings contain ONLY kind and logLines"));
            assertTrue(request.messages().getFirst().content().getFirst().text().length() < 2000);
            ConverseResponse response = realClient.converse(request.toBuilder()
                    .inferenceConfig(request.inferenceConfig().toBuilder().maxTokens(600).build()).build());
            var content = response.output() == null || response.output().message() == null ? List.of()
                    : response.output().message().content().stream()
                            .map(block -> block.text() == null ? "[non-text content]" : redactor.redact(block.text()))
                            .toList();
            Files.writeString(capture, mapper.writeValueAsString(Map.of(
                    "stopReason", response.stopReasonAsString(), "content", content,
                    "inputTokens", response.usage().inputTokens(), "outputTokens", response.usage().outputTokens(),
                    "maxOutputTokens", 600)));
            System.out.printf("Live tokens: input=%d output=%d maxOutput=600 stopReason=%s%n",
                    response.usage().inputTokens(), response.usage().outputTokens(), response.stopReasonAsString());
            return response;
        }).when(bedrock).converse(any(ConverseRequest.class));
        long runId = System.currentTimeMillis();
        assertTrue(repository.findRun(REPOSITORY, runId, 1).isEmpty(), "Refusing to overwrite an existing run");
        Instant now = Instant.now();
        var run = new GitHubWorkflowRun(runId, "Sanitized Bedrock smoke test", 1, 1, 1,
                "push", "completed", "failure", "synthetic-smoke", "a".repeat(40),
                "https://github.com/octo/bedrock-smoke/actions/runs/" + runId, now, now,
                new GitHubRepository(REPOSITORY_ID, "bedrock-smoke", REPOSITORY,
                        "https://github.com/octo/bedrock-smoke", false));
        var job = new GitHubJob(1, runId, "Compile sanitized sample", "completed", "failure",
                null, null, null, List.of());
        when(github.getWorkflowRunAttempt("octo", "bedrock-smoke", runId, 1)).thenReturn(run);
        when(github.listJobsForAttempt("octo", "bedrock-smoke", runId, 1, 1, 100))
                .thenReturn(new GitHubJobs(1, List.of(job)));
        when(github.getFailedJobLogs("octo", "bedrock-smoke", 1)).thenReturn(LOGS);
        byte[] payload = ("""
                {"action":"completed","repository":{"id":%d,"full_name":"%s"},
                 "workflow_run":{"id":%d,"run_attempt":1,"status":"completed","conclusion":"failure"}}
                """.formatted(REPOSITORY_ID, REPOSITORY, runId)).getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("synthetic-smoke-test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var consumer = new SqsWorkflowEventConsumer(sqs, mapper, worker, queueUrl, 120);
        sqs.createQueue(request -> request.queueName(QUEUE));
        try {
            mvc.perform(post("/api/webhooks/github").content(payload)
                            .header("X-GitHub-Event", "workflow_run")
                            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                            .header("X-Hub-Signature-256", signature))
                    .andExpect(status().isNoContent());
            var messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                    .maxNumberOfMessages(1).waitTimeSeconds(5)).messages();
            assertEquals(1, messages.size());
            consumer.consume(messages.getFirst());
            var stored = repository.findRun(REPOSITORY, runId, 1).orElseThrow();
            var failure = stored.analysis().failedJobs().getFirst();
            var analysis = failure.analysis();
            System.out.printf("Live smoke: repositoryId=%d runId=%d source=%s fallback=%s findings=%d%n",
                    REPOSITORY_ID, runId, analysis.analysisSource(), analysis.fallbackReason(),
                    analysis.diagnosticFindings().size());
            assertEquals("BEDROCK", analysis.analysisSource(), analysis.fallbackReason());
            assertNull(analysis.fallbackReason());
            assertEquals("us.amazon.nova-lite-v1:0", analysis.modelIdentifier());
            for (var finding : analysis.diagnosticFindings()) {
                assertFalse(finding.evidence().isEmpty());
                for (var evidence : finding.evidence()) {
                    assertTrue(evidence.lineNumber() >= 1 && evidence.lineNumber() <= LOGS.lines().count());
                    assertEquals(LOGS.lines().toList().get(evidence.lineNumber() - 1), evidence.text());
                }
                assertEquals(finding.kind() == com.githubci.intelligence.dto.DiagnosticFinding.Kind.OBSERVATION
                        ? com.githubci.intelligence.dto.ConfidenceLevel.HIGH
                        : com.githubci.intelligence.dto.ConfidenceLevel.LOW, finding.confidence());
            }
            assertEquals(new FailureAnalyzerService().analyze(LOGS).fingerprint(), analysis.fingerprint());
            assertEquals(LOGS, storage.retrieve(failure.logObjectKey()));
            mvc.perform(get("/api/repositories/" + REPOSITORY_ID + "/failures/" + runId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.failedJobs[0].analysis.analysisSource").value("BEDROCK"))
                    .andExpect(jsonPath("$.data.failedJobs[0].analysis.modelIdentifier")
                            .value("us.amazon.nova-lite-v1:0"));
            // A completed delivery cannot trigger another model invocation.
            mvc.perform(post("/api/webhooks/github").content(payload)
                            .header("X-GitHub-Event", "workflow_run")
                            .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                            .header("X-Hub-Signature-256", signature))
                    .andExpect(status().isNoContent());
            var duplicates = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                    .maxNumberOfMessages(1).waitTimeSeconds(5)).messages();
            assertEquals(1, duplicates.size());
            consumer.consume(duplicates.getFirst());
            verify(github, times(1)).getFailedJobLogs("octo", "bedrock-smoke", 1);
            verify(bedrock, times(1)).converse(any(ConverseRequest.class));
            assertTrue(sqs.receiveMessage(request -> request.queueUrl(queueUrl).waitTimeSeconds(1))
                    .messages().isEmpty());
        } finally {
            sqs.deleteQueue(request -> request.queueUrl(queueUrl));
        }
    }
}
