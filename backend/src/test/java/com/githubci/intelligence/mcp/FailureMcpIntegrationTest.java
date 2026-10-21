package com.githubci.intelligence.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.githubci.intelligence.dto.ConfidenceLevel;
import com.githubci.intelligence.dto.DiagnosticFinding;
import com.githubci.intelligence.dto.FailureAnalysis;
import com.githubci.intelligence.dto.FailureCategory;
import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubRepository;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.LogEvidence;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.models.StoredWorkflowAnalysis;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository.ReadPage;
import com.githubci.intelligence.services.WorkflowRunAnalysisService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=local", "app.mcp.enabled=true", "app.sqs.worker-enabled=false",
        "app.bedrock.enabled=false", "github.token=", "server.address=127.0.0.1"})
class FailureMcpIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-20T07:00:00Z");
    private static final GitHubRepository REPO = new GitHubRepository(
            1, "demo", "octo/demo", "https://github.com/octo/demo", true);
    @LocalServerPort
    private int port;
    @Autowired
    private JsonMapper mapper;
    @MockitoBean
    private WorkflowAnalysisRepository repository;
    @MockitoBean
    private WorkflowRunAnalysisService analysisService;
    @MockitoBean
    private BedrockRuntimeClient bedrock;
    private McpSyncClient client;

    @BeforeEach
    void connect() {
        when(repository.repository(1)).thenReturn(Optional.of(REPO));
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp").openConnectionOnStartup(false).build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
    }

    @AfterEach
    void close() {
        client.closeGracefully();
        verifyNoInteractions(analysisService, bedrock);
        verify(repository, never()).claim(anyString(), any());
        verify(repository, never()).complete(any(), any());
        verify(repository, never()).fail(any());
        verify(repository, never()).renew(any());
    }

    @Test
    void discoversOnlyFourReadOnlyToolsWithBoundedSchemas() {
        var tools = client.listTools().tools();
        assertEquals(List.of("failure_analysis", "recent_workflow_failures",
                        "repository_failure_statistics", "similar_workflow_failures"),
                tools.stream().map(McpSchema.Tool::name).sorted().toList());
        for (var tool : tools) {
            assertTrue(tool.annotations().readOnlyHint());
            assertFalse(tool.annotations().destructiveHint());
            assertTrue(tool.annotations().idempotentHint());
            assertEquals(false, tool.inputSchema().get("additionalProperties"));
            assertTrue(mapper.valueToTree(tool.inputSchema()).path("required").toString().contains("repositoryId"));
        }
    }

    @Test
    void readsRecentAndSimilarFailuresThroughExistingService() {
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(record(42)));
        when(repository.runs(REPO.fullName(), true, false, 10, Map.of()))
                .thenReturn(new ReadPage<>(List.of(record(42), record(43)), Map.of()));
        var recent = success("recent_workflow_failures", Map.of("repositoryId", 1));
        assertEquals(42, recent.at("/data/items/0/runId").asLong());
        assertEquals("CI", recent.at("/data/items/0/workflowName").asText());
        assertEquals("DYNAMODB", recent.path("storage").asText());
        assertTrue(recent.at("/data/nextToken").isNull());
        var similar = success("similar_workflow_failures", Map.of("repositoryId", 1, "runId", 42));
        assertEquals(1, similar.at("/data/items").size());
        assertEquals(43, similar.at("/data/items/0/runId").asLong());
    }

    @Test
    void returnsStoredBedrockFindingsEvidenceAndProvenanceWithoutReanalysis() {
        when(repository.findRun(REPO.fullName(), 42, 2)).thenReturn(Optional.of(record(42)));
        var result = success("failure_analysis", Map.of("repositoryId", 1, "runId", 42, "attempt", 2));
        assertEquals("BEDROCK", result.at("/data/failedJobs/0/analysis/analysisSource").asText());
        assertEquals("test-model", result.at("/data/failedJobs/0/analysis/modelIdentifier").asText());
        assertEquals("OBSERVATION", result.at("/data/failedJobs/0/analysis/diagnosticFindings/0/kind").asText());
        assertEquals(12, result.at("/data/failedJobs/0/analysis/diagnosticFindings/0/evidence/0/lineNumber").asInt());
        assertEquals("cannot find symbol", result.at("/data/failedJobs/0/analysis/evidence/0/text").asText());
        assertEquals(NOW.toString(), result.at("/data/run/analyzedAt").asText());
        assertFalse(result.at("/data/failedJobs/0/truncated").asBoolean());
        assertFalse(result.toString().contains("logObjectKey"));
        assertFalse(result.toString().contains("processingToken"));
        verify(repository).findRun(REPO.fullName(), 42, 2);
    }

    @Test
    void boundsDetailJobsAndExcerptsAndPreservesEvidenceLineReferences() {
        var original = record(42);
        var evidence = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(line -> new LogEvidence(line, "x".repeat(2000))).toList();
        var analysis = new FailureAnalysis(FailureCategory.JAVA_COMPILATION, "x".repeat(2000), "cause",
                "investigate", ConfidenceLevel.HIGH, evidence, evidence, "signature", "fingerprint");
        var jobs = java.util.stream.IntStream.range(0, 7).mapToObj(index ->
                new WorkflowRunAnalysis.FailedJobAnalysis(job(index), WorkflowRunAnalysis.LogStatus.AVAILABLE,
                        null, analysis, "private-key")).toList();
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(new StoredWorkflowAnalysis(
                original.repository(), original.workflowRun(), original.status(),
                new WorkflowRunAnalysis(REPO.fullName(), 42, jobs), NOW, NOW, null)));
        assertError("failure_analysis", Map.of("repositoryId", 1, "runId", 42, "jobLimit", 5), "RESPONSE_TOO_LARGE");
        var first = success("failure_analysis", Map.of("repositoryId", 1, "runId", 42, "jobLimit", 2));
        assertEquals(2, first.at("/data/failedJobs").size());
        assertEquals(7, first.at("/data/totalFailedJobs").asInt());
        assertEquals(2, first.at("/data/nextJobOffset").asInt());
        assertTrue(first.at("/data/failedJobs/0/truncated").asBoolean());
        assertEquals(10, first.at("/data/failedJobs/0/analysis/evidence").size());
        assertEquals(1000, first.at("/data/failedJobs/0/analysis/evidence/0/text").asText().length());
        assertEquals(1, first.at("/data/failedJobs/0/analysis/evidence/0/lineNumber").asInt());
        var last = success("failure_analysis", Map.of("repositoryId", 1, "runId", 42, "jobOffset", 5));
        assertEquals(2, last.at("/data/failedJobs").size());
        assertTrue(last.at("/data/nextJobOffset").isNull());
    }

    @Test
    void returnsRepositoryStatisticsOverPersistedAttempts() {
        when(repository.runs(REPO.fullName(), false, false, 100, Map.of()))
                .thenReturn(new ReadPage<>(List.of(record(42), record(43)), Map.of()));
        var result = success("repository_failure_statistics", Map.of("repositoryId", 1));
        assertEquals(2, result.at("/data/totalRuns").asInt());
        assertEquals(2, result.at("/data/failureRuns").asInt());
        assertEquals(2, result.at("/data/failuresByCategory/JAVA_COMPILATION").asInt());
    }

    @Test
    void forwardsCategoryAndOpaqueCursorWithoutChangingPageSemantics() {
        var key = Map.of("pk", value("REPO#octo/demo"),
                "sk", value("RUN#00000000000000000042#ATTEMPT#0000000002"),
                "failurePk", value("REPO#octo/demo"),
                "failureSk", value("2026-10-20T07:00:00.000Z#RUN#00000000000000000042#ATTEMPT#0000000002"));
        when(repository.runs(REPO.fullName(), true, false, 1, Map.of()))
                .thenReturn(new ReadPage<>(List.of(record(42)), key));
        when(repository.runs(REPO.fullName(), true, false, 1, key))
                .thenReturn(new ReadPage<>(List.of(record(43)), Map.of()));
        var first = success("recent_workflow_failures",
                Map.of("repositoryId", 1, "limit", 1, "category", "JAVA_COMPILATION"));
        String token = first.at("/data/nextToken").asText();
        assertFalse(token.isBlank());
        var second = success("recent_workflow_failures", Map.of("repositoryId", 1, "limit", 1,
                "category", "JAVA_COMPILATION", "nextToken", token));
        assertEquals(43, second.at("/data/items/0/runId").asLong());
        assertTrue(second.at("/data/nextToken").isNull());
        assertError("recent_workflow_failures",
                Map.of("repositoryId", 1, "limit", 1, "nextToken", token), "BAD_REQUEST");
    }

    @Test
    void enforcesSerializedResponseByteLimitRatherThanReturningAnUnboundedPayload() {
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(recordWithWorkflowName("")));
        var args = Map.<String, Object>of("repositoryId", 1, "runId", 42);
        var baseline = client.callTool(new McpSchema.CallToolRequest("failure_analysis", args));
        assertFalse(baseline.isError());
        int maxBytes = 256 * 1024;
        int nameLength = (maxBytes - mapper.writeValueAsBytes(baseline).length) / 2;
        when(repository.latestRun(REPO.fullName(), 42))
                .thenReturn(Optional.of(recordWithWorkflowName("x".repeat(nameLength))));
        var boundary = client.callTool(new McpSchema.CallToolRequest("failure_analysis", args));
        assertFalse(boundary.isError());
        int bytes = mapper.writeValueAsBytes(boundary).length;
        assertTrue(bytes <= maxBytes && bytes >= maxBytes - 1, "Verify the actual serialized result boundary");
        when(repository.latestRun(REPO.fullName(), 42))
                .thenReturn(Optional.of(recordWithWorkflowName("x".repeat(nameLength + 1))));
        assertError("failure_analysis", Map.of("repositoryId", 1, "runId", 42), "RESPONSE_TOO_LARGE");
    }

    @Test
    void preservesFallbackProvenanceAndReportsCorruptStoredPayloads() {
        var original = record(42);
        var analysis = original.analysis().failedJobs().getFirst().analysis();
        var fallback = new FailureAnalysis(analysis.category(), analysis.errorMessage(), analysis.suspectedCause(),
                analysis.suggestedInvestigation(), analysis.confidence(), analysis.evidence(), analysis.logContext(),
                analysis.normalizedSignature(), analysis.fingerprint(), "JAVA_FALLBACK", null,
                analysis.diagnosticFindings(), "Bedrock response failed evidence validation");
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(new StoredWorkflowAnalysis(
                original.repository(), original.workflowRun(), AnalysisStatus.COMPLETED,
                new WorkflowRunAnalysis(REPO.fullName(), 42, List.of(new WorkflowRunAnalysis.FailedJobAnalysis(
                        job(7), WorkflowRunAnalysis.LogStatus.AVAILABLE, null, fallback, null))), NOW, NOW, null)));
        var result = success("failure_analysis", Map.of("repositoryId", 1, "runId", 42));
        assertEquals("JAVA_FALLBACK", result.at("/data/failedJobs/0/analysis/analysisSource").asText());
        assertEquals(fallback.fallbackReason(), result.at("/data/failedJobs/0/analysis/fallbackReason").asText());
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(new StoredWorkflowAnalysis(
                original.repository(), original.workflowRun(), AnalysisStatus.COMPLETED, null, NOW, NOW, null)));
        assertError("failure_analysis", Map.of("repositoryId", 1, "runId", 42), "INTERNAL_SERVER_ERROR");
    }

    static List<Map<String, Object>> invalidArguments() {
        return List.of(Map.of(), Map.of("repositoryId", 0), Map.of("repositoryId", "1"),
                Map.of("repositoryId", 1.5), Map.of("repositoryId", 1, "limit", 21),
                Map.of("repositoryId", 1, "limit", -1), Map.of("repositoryId", 1, "limit", "10"),
                Map.of("repositoryId", 1, "category", "INVENTED"),
                Map.of("repositoryId", 1, "nextToken", ""), Map.of("repositoryId", 1, "nextToken", "x".repeat(4097)),
                Map.of("repositoryId", 1, "unknown", true),
                Map.of("repositoryId", new java.math.BigInteger("9223372036854775808")));
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void rejectsInvalidArgumentsWithoutReadingStorage(Map<String, Object> arguments) {
        var result = client.callTool(new McpSchema.CallToolRequest("recent_workflow_failures", arguments));
        assertInvalid(result);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidRunAttemptAndJobBoundsBeforeReadingStorage() {
        for (var args : List.of(Map.<String, Object>of("repositoryId", 1, "runId", 0),
                Map.<String, Object>of("repositoryId", 1, "runId", 42, "attempt", 0),
                Map.<String, Object>of("repositoryId", 1, "runId", 42, "jobLimit", 6),
                Map.<String, Object>of("repositoryId", 1, "runId", 42, "jobOffset", -1))) {
            var result = client.callTool(new McpSchema.CallToolRequest("failure_analysis", args));
            assertInvalid(result);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void reportsMissingRunsPendingAnalysesAndDatabaseErrorsExplicitly() {
        assertError("failure_analysis", Map.of("repositoryId", 99, "runId", 42), "NOT_FOUND");
        assertError("failure_analysis", Map.of("repositoryId", 1, "runId", 404), "NOT_FOUND");
        var run = record(42);
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(new StoredWorkflowAnalysis(
                run.repository(), run.workflowRun(), AnalysisStatus.PROCESSING, null, NOW, NOW, NOW)));
        assertError("failure_analysis", Map.of("repositoryId", 1, "runId", 42), "CONFLICT");
        when(repository.repository(1)).thenThrow(DynamoDbException.builder().message("sensitive detail").build());
        var error = assertError("repository_failure_statistics", Map.of("repositoryId", 1), "SERVICE_UNAVAILABLE");
        assertFalse(error.toString().contains("sensitive detail"));
    }

    @Test
    void rejectsInvalidCursorAndReportsNoSimilarityFingerprint() {
        assertError("recent_workflow_failures", Map.of("repositoryId", 1, "nextToken", "invalid"), "BAD_REQUEST");
        var original = record(42);
        var unavailable = new WorkflowRunAnalysis.FailedJobAnalysis(job(7),
                WorkflowRunAnalysis.LogStatus.UNAVAILABLE, "expired", null, null);
        when(repository.latestRun(REPO.fullName(), 42)).thenReturn(Optional.of(new StoredWorkflowAnalysis(
                original.repository(), original.workflowRun(), original.status(),
                new WorkflowRunAnalysis(REPO.fullName(), 42, List.of(unavailable)), NOW, NOW, null)));
        var detail = success("failure_analysis", Map.of("repositoryId", 1, "runId", 42));
        assertTrue(detail.at("/data/failedJobs/0/analysis").isNull());
        assertFalse(detail.at("/data/run/analysisComplete").asBoolean());
        assertError("similar_workflow_failures", Map.of("repositoryId", 1, "runId", 42), "CONFLICT");
    }

    @Test
    void returnsProtocolErrorForUnknownTool() {
        assertThrows(McpError.class, () -> client.callTool(new McpSchema.CallToolRequest("analyze", Map.of())));
        verifyNoInteractions(repository);
    }

    @Test
    void handlesStatelessRequestsWithoutSessionAndRejectsStreamingGet() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
            assertEquals(4, mapper.readTree(response.body()).at("/result/tools").size());
            var get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, get.statusCode());
        }
    }

    @Test
    void rejectsOversizedBodiesAndUnsupportedContentTypesAtTransportBoundary() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
            var oversized = http.send(HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(16 * 1024 + 1))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(413, oversized.statusCode());
            var wrongType = http.send(HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "text/plain")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(415, wrongType.statusCode());
            var crossOrigin = http.send(HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json").header("Origin", "http://attacker.example")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, crossOrigin.statusCode());
        }
        verifyNoInteractions(repository);
    }

    private JsonNode success(String tool, Map<String, Object> args) {
        var result = client.callTool(new McpSchema.CallToolRequest(tool, args));
        assertFalse(result.isError(), () -> result.toString());
        assertTrue(mapper.writeValueAsBytes(result).length <= 256 * 1024);
        assertEquals(mapper.readTree(((McpSchema.TextContent) result.content().getFirst()).text()), tree(result));
        return tree(result);
    }

    private JsonNode assertError(String tool, Map<String, Object> args, String code) {
        var result = client.callTool(new McpSchema.CallToolRequest(tool, args));
        assertTrue(result.isError());
        assertEquals(code, tree(result).at("/error/code").asText());
        return tree(result);
    }

    private JsonNode tree(McpSchema.CallToolResult result) {
        return mapper.valueToTree(result.structuredContent());
    }

    private void assertInvalid(McpSchema.CallToolResult result) {
        assertTrue(result.isError());
        if (result.structuredContent() != null) {
            assertEquals("INVALID_ARGUMENT", tree(result).at("/error/code").asText());
        } else {
            assertTrue(((McpSchema.TextContent) result.content().getFirst()).text().contains("Validation failed"));
        }
    }

    private static StoredWorkflowAnalysis record(long id) {
        var evidence = List.of(new LogEvidence(12, "cannot find symbol"));
        var analysis = new FailureAnalysis(FailureCategory.JAVA_COMPILATION, "cannot find symbol", "missing import",
                "inspect imports", ConfidenceLevel.HIGH, evidence, evidence, "signature", "fingerprint",
                "BEDROCK", "test-model", List.of(new DiagnosticFinding(
                        DiagnosticFinding.Kind.OBSERVATION, "compiler error", ConfidenceLevel.HIGH, evidence)), null);
        var run = new GitHubWorkflowRun(id, "CI", 5, id, 2, "push", "completed", "failure", "main",
                "abc123", "https://github.com/octo/demo/actions/runs/" + id, NOW, NOW, REPO);
        return new StoredWorkflowAnalysis(REPO.fullName(), run, AnalysisStatus.COMPLETED,
                new WorkflowRunAnalysis(REPO.fullName(), id, List.of(new WorkflowRunAnalysis.FailedJobAnalysis(
                        job(7), WorkflowRunAnalysis.LogStatus.AVAILABLE, null, analysis, "private-key"))),
                NOW, NOW, null);
    }

    private static GitHubJob job(long id) {
        return new GitHubJob(id, 42, "compile", "completed", "failure", null, NOW, NOW, List.of());
    }

    private static AttributeValue value(String text) {
        return AttributeValue.builder().s(text).build();
    }

    private static StoredWorkflowAnalysis recordWithWorkflowName(String name) {
        var original = record(42);
        var run = original.workflowRun();
        var renamed = new GitHubWorkflowRun(run.id(), name, run.workflowId(), run.runNumber(), run.runAttempt(),
                run.event(), run.status(), run.conclusion(), run.headBranch(), run.headSha(), run.htmlUrl(),
                NOW, NOW, REPO);
        return new StoredWorkflowAnalysis(original.repository(), renamed, original.status(), original.analysis(),
                NOW, NOW, null);
    }
}
