package com.githubci.intelligence.services;

import java.util.List;
import java.nio.charset.StandardCharsets;

import com.githubci.intelligence.dto.*;
import com.githubci.intelligence.exceptions.GitHubException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextualFailureAnalyzerTest {
    private final BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
    private final GitHubService github = mock(GitHubService.class);
    private final LogRedactionService redactor = new LogRedactionService("test-secret-token", "");
    private final FailureAnalyzerService javaAnalyzer = new FailureAnalyzerService();
    private final GitHubJob job = new GitHubJob(7, 39549084142L, "Database check", "completed", "failure",
            null, null, null, List.of(new GitHubJobStep("Check database", 2, "completed", "failure", null, null)));
    private static final String LOGS = """
            ##[group]Run echo "Error: Could not connect to PostgreSQL"
              echo "Error: Could not connect to PostgreSQL"
              exit 1
            ##[endgroup]
            Error: Could not connect to PostgreSQL
            Error: Process completed with exit code 1.
            """;
    private ContextualFailureAnalyzer analyzer;

    @BeforeEach
    void setup() {
        analyzer = analyzer(true, false);
    }

    private ContextualFailureAnalyzer analyzer(boolean enabled, boolean changes) {
        @SuppressWarnings("unchecked")
        ObjectProvider<BedrockRuntimeClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(enabled ? client : null);
        return new ContextualFailureAnalyzer(provider, JsonMapper.builder().build(), redactor, github,
                "test-model", changes);
    }

    private ConverseResponse response(String json) {
        return ConverseResponse.builder().stopReason(StopReason.END_TURN)
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText(json)).build())).build();
    }

    private String validJson() {
        return """
                {"suspectedCause":"The step may intentionally fail; no outage is established.",
                 "suggestedInvestigation":"Inspect the workflow command rather than restarting PostgreSQL.",
                 "findings":[
                   {"kind":"OBSERVATION","logLines":[2,3,5]},
                   {"kind":"HYPOTHESIS","description":"This may be a simulated failure.",
                    "logLines":[2,3]}]}
                """;
    }

    private FailureAnalysis analyze(String logs) {
        return analyzer.analyze(logs, job, "octo", "demo", "a".repeat(40), javaAnalyzer.analyze(logs));
    }

    @Test
    void explainsEchoWithoutPromotingClassificationAndPersistsProvenanceAndExactReferences() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(validJson()));
        FailureAnalysis result = analyze(LOGS);
        assertEquals(FailureCategory.UNKNOWN, result.category());
        assertEquals(ConfidenceLevel.LOW, result.confidence());
        assertEquals("BEDROCK", result.analysisSource());
        assertEquals("test-model", result.modelIdentifier());
        assertEquals(2, result.diagnosticFindings().size());
        assertEquals(ConfidenceLevel.HIGH, result.diagnosticFindings().getFirst().confidence());
        assertEquals("  echo \"Error: Could not connect to PostgreSQL\"",
                result.diagnosticFindings().getFirst().description());
        assertEquals(ConfidenceLevel.LOW, result.diagnosticFindings().get(1).confidence());
        assertEquals(new LogEvidence(5, "Error: Could not connect to PostgreSQL"),
                result.diagnosticFindings().getFirst().evidence().get(2));
        assertEquals(javaAnalyzer.analyze(LOGS).fingerprint(), result.fingerprint());
        var request = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(request.capture());
        assertEquals("test-model", request.getValue().modelId());
        assertEquals(1800, request.getValue().inferenceConfig().maxTokens());
        assertTrue(request.getValue().messages().getFirst().content().getFirst().text().contains("Check database"));
        assertTrue(request.getValue().system().getFirst().text().contains(
                "Never return confidence fields"));
        assertTrue(request.getValue().system().getFirst().text().contains(
                "\"$schema\": \"https://json-schema.org/draft/2020-12/schema\""));
        verifyNoInteractions(github);
        var mapper = JsonMapper.builder().build();
        assertEquals(result, mapper.readValue(mapper.writeValueAsString(result), FailureAnalysis.class));
    }

    @Test
    void sanitizesAllContextAndReturnedText() {
        analyzer = analyzer(true, true);
        when(github.getCommit(anyString(), anyString(), anyString())).thenReturn(new GitHubCommit(List.of(
                new GitHubCommit.FileChange("workflow.yml", "modified", "+token=test-secret-token"))));
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(
                validJson().replace("simulated failure", "simulated failure test-secret-token")));
        var result = analyze(LOGS + "token=test-secret-token\n");
        var request = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(request.capture());
        String input = request.getValue().messages().getFirst().content().getFirst().text();
        assertFalse(input.contains("test-secret-token"));
        assertTrue(input.contains("[REDACTED]"));
        assertFalse(result.diagnosticFindings().get(1).description().contains("test-secret-token"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "{}", "```json\n{}\n```"})
    void rejectsMalformedResponses(String json) {
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(json));
        assertEquals("BEDROCK_INVALID_RESPONSE", analyze(LOGS).fallbackReason());
    }

    @Test
    void rejectsUnknownReferencesUnsupportedFindingsModelConfidenceMissingEvidenceAndExtraFields() {
        for (String json : List.of(
                validJson().replace("[2,3,5]", "[999]"),
                validJson().replace("[2,3,5]", "[0]"),
                validJson().replace("[2,3,5]", "[-1]"),
                validJson().replace("[2,3,5]", "[2,2]"),
                validJson().replace("[2,3,5]", "[2147483648]"),
                validJson().replace("[2,3,5]", "[\"2\"]"),
                validJson().replace("[2,3,5]", "null"),
                validJson().replace("\"OBSERVATION\"", "\"CONFIRMED\""),
                validJson().replace("\"OBSERVATION\"", "null"),
                validJson().replace("\"kind\":\"OBSERVATION\"", "\"kind\":\"OBSERVATION\",\"confidence\":\"HIGH\""),
                validJson().replace("\"kind\":\"HYPOTHESIS\"", "\"kind\":\"HYPOTHESIS\",\"confidence\":\"MEDIUM\""),
                validJson().replace("[2,3,5]", "[]"),
                validJson().replace("[2,3]", "[]"),
                validJson().replace("\"findings\":", "\"extra\":true,\"findings\":"),
                validJson().replace("\"kind\":\"OBSERVATION\"",
                        "\"kind\":\"OBSERVATION\",\"description\":\"The database is down\""),
                validJson().replace("\"This may be a simulated failure.\"", "\" \""),
                validJson().replace("\"This may be a simulated failure.\"", "\"" + "x".repeat(1501) + "\""),
                validJson() + "{}",
                validJson().replace("\"logLines\":[2,3]", "\"logLines\":[2.5]"))) {
            when(client.converse(any(ConverseRequest.class))).thenReturn(response(json));
            var result = analyze(LOGS);
            assertEquals("JAVA", result.analysisSource());
            assertEquals("BEDROCK_INVALID_RESPONSE", result.fallbackReason());
            assertEquals(javaAnalyzer.analyze(LOGS).suspectedCause(), result.suspectedCause());
        }
    }

    @Test
    void fallsBackOnSdkFailureOrTruncatedResponse() {
        when(client.converse(any(ConverseRequest.class))).thenThrow(SdkClientException.create("private payload"));
        assertEquals("BEDROCK_UNAVAILABLE", analyze(LOGS).fallbackReason());
        when(client.converse(any(ConverseRequest.class))).thenReturn(
                response(validJson()).toBuilder().stopReason(StopReason.MAX_TOKENS).build());
        assertEquals("BEDROCK_INVALID_RESPONSE", analyze(LOGS).fallbackReason());
    }

    @Test
    void disabledPathMakesNoAwsOrGitHubCalls() {
        analyzer = analyzer(false, true);
        assertEquals(javaAnalyzer.analyze(LOGS), analyze(LOGS));
        verifyNoInteractions(client, github);
    }

    @Test
    void capturedNovaLiteResponseReproducesOldContractFailureAndMigratesToIdOnlyEvidence() throws Exception {
        String text;
        try (var fixture = getClass().getResourceAsStream("/bedrock/nova-lite-invalid-response.json")) {
            assertNotNull(fixture);
            text = JsonMapper.builder().build().readTree(
                    new String(fixture.readAllBytes(), StandardCharsets.UTF_8))
                    .get("content").get(0).asString();
        }
        String unfenced = text.substring("```json\n".length(), text.length() - "\n```".length());
        String logs = """
                ##[group]Run mvn compile
                [ERROR] /src/App.java:[12,4] cannot find symbol
                [ERROR] symbol:   variable missingValue
                [ERROR] BUILD FAILURE
                ##[endgroup]
                Error: Process completed with exit code 1.
                """;
        for (String candidate : List.of(text, unfenced)) {
            when(client.converse(any(ConverseRequest.class))).thenReturn(response(candidate));
            var result = analyze(logs);
            assertEquals("JAVA", result.analysisSource());
            assertEquals("BEDROCK_INVALID_RESPONSE", result.fallbackReason());
            assertNull(result.modelIdentifier());
        }
        var mapper = JsonMapper.builder().build();
        var migrated = mapper.readTree(unfenced);
        for (var finding : migrated.get("findings")) {
            var object = (tools.jackson.databind.node.ObjectNode) finding;
            object.remove("confidence");
            if ("OBSERVATION".equals(object.get("kind").asString())) object.remove("description");
        }
        when(client.converse(any(ConverseRequest.class))).thenReturn(
                response("```json\n" + mapper.writeValueAsString(migrated) + "\n```"));
        var result = analyze(logs);
        assertEquals("BEDROCK", result.analysisSource());
        assertNull(result.fallbackReason());
        assertEquals("##[group]Run mvn compile", result.diagnosticFindings().getFirst().description());
        assertEquals(new LogEvidence(1, "##[group]Run mvn compile"),
                result.diagnosticFindings().getFirst().evidence().getFirst());
        assertEquals(ConfidenceLevel.HIGH, result.diagnosticFindings().getFirst().confidence());
        assertEquals(ConfidenceLevel.LOW, result.diagnosticFindings().getLast().confidence());
        assertEquals(javaAnalyzer.analyze(logs).fingerprint(), result.fingerprint());
    }

    @ParameterizedTest
    @ValueSource(strings = {"```json\n%s\n```", "```\n%s\n```", " \n```json\r\n%s\r\n```\r\n ",
            " \n%s\t"})
    void acceptsOnlyAnOptionalWholeResponseJsonFence(String envelope) {
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(envelope.formatted(validJson())));
        assertEquals("BEDROCK", analyze(LOGS).analysisSource());
    }

    @Test
    void malformedOfflineFixturesAlwaysUseJavaFallback() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/bedrock/malformed-outputs.json")) {
            assertNotNull(fixture);
            var cases = JsonMapper.builder().build().readTree(
                    new String(fixture.readAllBytes(), StandardCharsets.UTF_8));
            for (var testCase : cases) {
                String candidate = testCase.get("output").asString().replace("$VALID", validJson());
                when(client.converse(any(ConverseRequest.class))).thenReturn(response(candidate));
                var result = analyze(LOGS);
                assertEquals("JAVA", result.analysisSource(), testCase.get("name").asString());
                assertEquals("BEDROCK_INVALID_RESPONSE", result.fallbackReason(), testCase.get("name").asString());
                assertEquals(javaAnalyzer.analyze(LOGS).diagnosticFindings(), result.diagnosticFindings());
            }
        }
    }

    @Test
    void resolvesExactSanitizedOriginalRatherThanTruncatedPromptAndBoundsPersistedEvidence() {
        String logs = "x".repeat(1200) + " test-secret-token";
        String json = validJson().replace("[2,3,5]", "[1]").replace("[2,3]", "[1]");
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(json));
        var result = analyze(logs);
        assertEquals("BEDROCK", result.analysisSource());
        var finding = result.diagnosticFindings().getFirst();
        assertEquals(redactor.redact(logs), finding.evidence().getFirst().text());
        assertEquals(finding.evidence().getFirst().text(), finding.description());
        assertFalse(finding.description().contains("test-secret-token"));
        var request = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(request.capture());
        String prompt = request.getValue().messages().getFirst().content().getFirst().text();
        assertEquals(500, JsonMapper.builder().build().readTree(prompt).get("logs").get("1").asString().length());
        assertEquals("BEDROCK_INVALID_RESPONSE", analyze("x".repeat(2001)).fallbackReason());
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 501, 2000})
    void preservesExactEvidenceAtPromptAndPersistenceBoundaries(int length) {
        String logs = "x".repeat(length);
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(
                validJson().replace("[2,3,5]", "[1]").replace("[2,3]", "[1]")));
        var result = analyze(logs);
        assertEquals("BEDROCK", result.analysisSource());
        assertEquals(logs, result.diagnosticFindings().getFirst().description());
        assertEquals(new LogEvidence(1, logs), result.diagnosticFindings().getFirst().evidence().getFirst());
    }

    @Test
    void enforcesResponseTextAndReferenceLimitsAtTheirExactBoundaries() {
        String json = validJson().replace("This may be a simulated failure.", "x".repeat(1500))
                .replace("[2,3,5]", "[1,2,3,4,5,6,7,8]");
        String bounded = json + " ".repeat(16000 - json.length());
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(bounded));
        assertEquals("BEDROCK", analyze("output\n".repeat(8)).analysisSource());
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(bounded + " "));
        assertEquals("BEDROCK_INVALID_RESPONSE", analyze("output\n".repeat(8)).fallbackReason());
    }

    @Test
    void rejectsExistingButUnsubmittedLineIdentifiersAndTooManyReferences() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(
                response(validJson().replace("[2,3,5]", "[50]")));
        assertEquals("BEDROCK_INVALID_RESPONSE", analyze("output\n".repeat(200)).fallbackReason());
        when(client.converse(any(ConverseRequest.class))).thenReturn(
                response(validJson().replace("[2,3,5]", "[1,2,3,4,5,6,7,8,9]")));
        assertEquals("BEDROCK_INVALID_RESPONSE", analyze("output\n".repeat(10)).fallbackReason());
    }

    @Test
    void missingCommitPermissionDoesNotPreventLogExplanation() {
        analyzer = analyzer(true, true);
        when(github.getCommit(anyString(), anyString(), anyString()))
                .thenThrow(new GitHubException(HttpStatus.FORBIDDEN, "Access denied"));
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(validJson()));
        assertEquals("BEDROCK", analyze(LOGS).analysisSource());
    }

    @Test
    void boundsLogExcerptsAndPatchesAndOmitsUnrelatedCommitFiles() {
        analyzer = analyzer(true, true);
        when(github.getCommit(anyString(), anyString(), anyString())).thenReturn(new GitHubCommit(List.of(
                new GitHubCommit.FileChange(".github/workflows/ci.yml", "modified", "p".repeat(5000)),
                new GitHubCommit.FileChange("unrelated.txt", "modified", "not relevant"))));
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(validJson()));
        analyze(LOGS + ("x".repeat(2000) + "\n").repeat(200));
        var request = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(request.capture());
        var input = JsonMapper.builder().build().readTree(
                request.getValue().messages().getFirst().content().getFirst().text());
        assertTrue(input.get("logs").size() <= 100);
        input.get("logs").forEach(line -> assertTrue(line.asString().length() <= 500));
        assertEquals(1, input.get("commitChanges").get("files").size());
        assertEquals(1500, input.get("commitChanges").get("files").get(0).get("patch").asString().length());
    }

    @Test
    void oldPersistedAnalysisRemainsReadableWithoutInventingNewDiagnostics() {
        var mapper = JsonMapper.builder().build();
        var tree = mapper.valueToTree(javaAnalyzer.analyze(LOGS));
        var old = (tools.jackson.databind.node.ObjectNode) tree;
        old.remove("analysisSource");
        old.remove("diagnosticFindings");
        var decoded = mapper.treeToValue(old, FailureAnalysis.class);
        assertEquals("LEGACY_JAVA", decoded.analysisSource());
        assertTrue(decoded.diagnosticFindings().isEmpty());
    }
}
