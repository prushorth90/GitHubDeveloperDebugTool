package com.githubci.intelligence.services;

import java.util.stream.Stream;

import com.githubci.intelligence.dto.ConfidenceLevel;
import com.githubci.intelligence.dto.FailureAnalysis;
import com.githubci.intelligence.dto.FailureCategory;
import com.githubci.intelligence.dto.LogEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureAnalyzerServiceTest {

    private final FailureAnalyzerService analyzer = new FailureAnalyzerService();

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(FailureCategory.JAVA_COMPILATION,
                        "[ERROR] /workspace/src/main/java/App.java:[12,8] cannot find symbol"),
                Arguments.of(FailureCategory.JAVA_COMPILATION,
                        "src/App.java:12: error: incompatible types: String cannot be converted to int"),
                Arguments.of(FailureCategory.JAVA_COMPILATION,
                        "[ERROR] Fatal error compiling: error: release version 25 not supported"),
                Arguments.of(FailureCategory.MAVEN_DEPENDENCY,
                        "[ERROR] Could not resolve dependencies for project com.example:app:jar:1.0"),
                Arguments.of(FailureCategory.MAVEN_DEPENDENCY,
                        "[ERROR] Could not find artifact com.example:library:jar:2.0 in central"),
                Arguments.of(FailureCategory.MAVEN_DEPENDENCY,
                        "[FATAL] Non-resolvable parent POM for com.example:app:1.0"),
                Arguments.of(FailureCategory.JUNIT_TEST,
                        "[ERROR] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0"),
                Arguments.of(FailureCategory.JUNIT_TEST,
                        "[ERROR] Tests run: 12, Failures: 0, Errors: 2, Skipped: 0"),
                Arguments.of(FailureCategory.JUNIT_TEST,
                        "org.opentest4j.AssertionFailedError: expected: <200> but was: <500>"),
                Arguments.of(FailureCategory.JUNIT_TEST,
                        "java.lang.AssertionError: expected true\n    at org.junit.Assert.fail(Assert.java:89)"),
                Arguments.of(FailureCategory.POSTGRESQL_CONNECTION,
                        "org.postgresql.util.PSQLException: Connection to localhost:5432 refused."),
                Arguments.of(FailureCategory.POSTGRESQL_CONNECTION,
                        "psql: error: connection to server at \"db\", port 5432 failed: Connection refused"),
                Arguments.of(FailureCategory.POSTGRESQL_CONNECTION,
                        "Connecting to jdbc:postgresql://db:5432/app\nError: connect ECONNREFUSED"),
                Arguments.of(FailureCategory.POSTGRESQL_CONNECTION,
                        "org.postgresql.util.PSQLException: The connection attempt failed.\n"
                                + "Caused by: java.net.ConnectException: Connection refused"),
                Arguments.of(FailureCategory.POSTGRESQL_CONNECTION,
                        "org.postgresql.util.PSQLException: FATAL: password authentication failed for user \"ci\""),
                Arguments.of(FailureCategory.AUTHENTICATION,
                        "fatal: Authentication failed for 'https://github.com/example/demo.git/'"),
                Arguments.of(FailureCategory.AUTHENTICATION,
                        "Error: Bad credentials"),
                Arguments.of(FailureCategory.AUTHENTICATION,
                        "git@github.com: Permission denied (publickey)."),
                Arguments.of(FailureCategory.AUTHENTICATION,
                        "Request failed with status code 401"),
                Arguments.of(FailureCategory.AUTHENTICATION,
                        "Could not transfer artifact example:app:jar:1 from private: status code: 401"),
                Arguments.of(FailureCategory.AUTHENTICATION,
                        "ERROR: failed to solve: registry: unauthorized: authentication required"),
                Arguments.of(FailureCategory.GITHUB_ACTIONS_CONFIGURATION,
                        "Invalid workflow file: .github/workflows/ci.yml#L12"),
                Arguments.of(FailureCategory.GITHUB_ACTIONS_CONFIGURATION,
                        ".github/workflows/ci.yml (Line: 10, Col: 5): Unexpected value 'steps'"),
                Arguments.of(FailureCategory.GITHUB_ACTIONS_CONFIGURATION,
                        "Error: Unable to resolve action `example/missing@v1`, unable to find version `v1`"),
                Arguments.of(FailureCategory.GITHUB_ACTIONS_CONFIGURATION,
                        "Error: Can't find 'action.yml', 'action.yaml' or 'Dockerfile' under '/work/action'"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void classifiesSpecificDiagnostics(FailureCategory expected, String log) {
        FailureAnalysis result = analyzer.analyze(log);

        assertEquals(expected, result.category());
        assertEquals(ConfidenceLevel.HIGH, result.confidence());
        assertFalse(result.errorMessage().isBlank());
        assertTrue(result.suspectedCause().contains("may"));
        assertFalse(result.suggestedInvestigation().isBlank());
        assertFalse(result.evidence().isEmpty());
        assertFalse(result.logContext().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "#8 ERROR: failed to solve: process \"/bin/sh -c npm ci\" did not complete successfully: exit code: 1",
            "Dockerfile:14\nRUN missing-command\n/bin/sh: missing-command: not found",
            "COPY failed: file not found in build context or excluded by .dockerignore",
            "failed to compute cache key: failed to calculate checksum: \"/app.jar\": not found",
            "The command '/bin/sh -c make' returned a non-zero code: 2"
    })
    void classifiesDockerBuildWrappersWithoutClaimingARootCause(String log) {
        FailureAnalysis result = analyzer.analyze(log);
        assertEquals(FailureCategory.DOCKER_BUILD, result.category());
        assertEquals(ConfidenceLevel.MEDIUM, result.confidence());
        assertTrue(result.suggestedInvestigation().contains("does not establish the root cause"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Error: connect ECONNREFUSED 127.0.0.1:3000",
            "Error: connect ECONNREFUSED 127.0.0.1:5432",
            "java.net.ConnectException: Connection refused",
            "Started PostgreSQL service\nError: connect ECONNREFUSED 127.0.0.1:6379",
            "Connecting to jdbc:postgresql://db:5432/app\nError: connect ECONNREFUSED 127.0.0.1:6379",
            "Connecting to jdbc:postgresql://db:5432/app\n##[endgroup]\nError: connect ECONNREFUSED",
            "Connecting to jdbc:postgresql://db:5432/app\n##[group]Frontend\nError: connect ECONNREFUSED",
            "Connecting to jdbc:postgresql://db:5432/app\n##[command]npm test\nError: connect ECONNREFUSED",
            "PostgreSQL service is healthy\nDownloading packages\nStarting frontend\nError: connect ECONNREFUSED",
            "java.lang.AssertionError: unrelated application invariant",
            "HTTP 500 Internal Server Error",
            "Error: Process completed with exit code 1.",
            "Failed to parse config.yaml",
            "Run echo 'Invalid workflow file'",
            "##[command]echo 'authentication failed'",
            "401 records processed successfully",
            "[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0",
            "[INFO] BUILD SUCCESS",
            "",
            " \n\t"
    })
    void keepsUnsupportedOrInsufficientEvidenceUnknown(String log) {
        FailureAnalysis result = analyzer.analyze(log);
        assertEquals(FailureCategory.UNKNOWN, result.category());
        assertEquals(ConfidenceLevel.LOW, result.confidence());
        assertFalse(result.errorMessage().isBlank());
        assertTrue(result.suspectedCause().contains("does not establish"));
    }

    @Test
    void capturesCleanDiagnosticAndSurroundingLinesWithOriginalLineNumbers() {
        FailureAnalysis result = analyzer.analyze("""
                2026-10-19T01:02:03.123Z [INFO] Compiling 1 source file

                2026-10-19T01:02:03.456Z \u001B[31m[ERROR] /src/App.java:[4,9] cannot find symbol\u001B[0m
                2026-10-19T01:02:03.457Z [ERROR]   symbol: class MissingType
                2026-10-19T01:02:03.458Z [ERROR]   location: class App
                2026-10-19T01:02:04.000Z [ERROR] BUILD FAILURE
                """);

        assertEquals("[ERROR] /src/App.java:[4,9] cannot find symbol", result.errorMessage());
        assertEquals(new LogEvidence(3, result.errorMessage()), result.evidence().getFirst());
        assertEquals(4, result.logContext().size());
        assertEquals(1, result.logContext().getFirst().lineNumber());
        assertEquals("[ERROR]   location: class App", result.logContext().getLast().text());
        assertFalse(result.suspectedCause().contains("MissingType"));
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().clear());
    }

    @Test
    void includesBothConnectionErrorAndCorroboratingPostgresEvidence() {
        FailureAnalysis result = analyzer.analyze(
                "Connecting to jdbc:postgresql://db/app\r\nError: connect ECONNREFUSED\r\nRetrying");
        assertEquals(FailureCategory.POSTGRESQL_CONNECTION, result.category());
        assertEquals(2, result.evidence().size());
        assertEquals(1, result.evidence().getFirst().lineNumber());
        assertEquals(2, result.evidence().getLast().lineNumber());
    }

    @Test
    void prefersSpecificCompilerEvidenceOverDockerAndMavenWrappers() {
        FailureAnalysis result = analyzer.analyze("""
                [ERROR] COMPILATION ERROR
                [ERROR] /app/App.java:[8,3] cannot find symbol
                [ERROR] Failed to execute goal maven-compiler-plugin:compile: Compilation failure
                ERROR: failed to solve: process "/bin/sh -c mvn package" did not complete successfully
                """);
        assertEquals(FailureCategory.JAVA_COMPILATION, result.category());
        assertEquals(ConfidenceLevel.HIGH, result.confidence());
        assertEquals(2, result.evidence().getFirst().lineNumber());
    }

    @Test
    void returnsUnknownForConflictingStrongDiagnosticsWithoutInventingACausalOrder() {
        FailureAnalysis result = analyzer.analyze("""
                [ERROR] /app/App.java:[8,3] cannot find symbol
                [ERROR] Tests run: 2, Failures: 1, Errors: 0
                """);
        assertEquals(FailureCategory.UNKNOWN, result.category());
        assertEquals(ConfidenceLevel.LOW, result.confidence());
        assertEquals(2, result.evidence().size());
        assertTrue(result.suspectedCause().contains("Multiple failure categories"));
        assertEquals(result, analyzer.analyze("""
                [ERROR] /app/App.java:[8,3] cannot find symbol
                [ERROR] Tests run: 2, Failures: 1, Errors: 0
                """));
    }

    @Test
    void preservesGenericObservedErrorWithoutGuessingItsCause() {
        FailureAnalysis result = analyzer.analyze("Starting server\nError: connect ECONNREFUSED\nDone");
        assertEquals("Error: connect ECONNREFUSED", result.errorMessage());
        assertEquals(new LogEvidence(2, result.errorMessage()), result.evidence().getFirst());
        assertEquals(3, result.logContext().size());
    }

    @Test
    void fingerprintsExtractedDiagnosticsRatherThanRawLogs() {
        FailureAnalysis first = analyzer.analyze("""
                2026-10-19T01:02:03Z Preparing first job
                2026-10-19T01:02:04Z Error: Bad credentials
                Completed in 12 seconds
                """);
        FailureAnalysis second = analyzer.analyze("""
                2026-10-20T09:08:07Z Preparing another job
                2026-10-20T09:08:08Z Error: Bad credentials
                Completed in 99 seconds
                """);
        assertEquals("Error: Bad credentials", first.normalizedSignature());
        assertEquals(first.normalizedSignature(), second.normalizedSignature());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertNotEquals(first.fingerprint(),
                analyzer.analyze("Error: Unable to resolve action `example/missing@v1`").fingerprint());
        assertNotEquals(first.fingerprint(), analyzer.analyze("Error: token expired").fingerprint());
    }

    @Test
    void fingerprintsEmbeddedTimestampsAndRetainsOriginalDiagnostic() {
        FailureAnalysis first = analyzer.analyze("Error: timeout at 2026-10-19T01:02:03Z");
        FailureAnalysis second = analyzer.analyze("Error: timeout at 2026-10-20T04:05:06Z");
        assertEquals("Error: timeout at", first.normalizedSignature());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertNotEquals(first.errorMessage(), second.errorMessage());
    }

    @Test
    void includesAllConflictingDiagnosticsButNotSurroundingContextInFingerprint() {
        String compiler = "[ERROR] /app/App.java:[8,3] cannot find symbol\n";
        FailureAnalysis first = analyzer.analyze(compiler + "Error: Bad credentials");
        FailureAnalysis second = analyzer.analyze(compiler + "Error: token expired");
        assertEquals(first.category(), second.category());
        assertEquals(first.errorMessage(), second.errorMessage());
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void fingerprintsUnknownAndEmptyLogsExplicitly() {
        FailureAnalysis empty = analyzer.analyze("");
        assertEquals("No recognizable failure diagnostic found.", empty.normalizedSignature());
        assertEquals(empty.fingerprint(), analyzer.analyze("Build successful").fingerprint());
        assertNotEquals(empty.fingerprint(), analyzer.analyze("Error: unrecognized issue").fingerprint());
    }

    @Test
    void rejectsNullRatherThanTreatingItAsAnAnalyzedLog() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> analyzer.analyze(null));
        assertEquals("Raw job logs must not be null", exception.getMessage());
    }
}
