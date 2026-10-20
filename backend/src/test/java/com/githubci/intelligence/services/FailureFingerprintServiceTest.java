package com.githubci.intelligence.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;

import com.githubci.intelligence.dto.FailureCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.githubci.intelligence.dto.FailureCategory.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailureFingerprintServiceTest {

    private final FailureFingerprintService service = new FailureFingerprintService();

    static Stream<Arguments> equivalentSignatures() {
        return Stream.of(
                Arguments.of("Error at 2026-10-19T01:02:03.123Z: timeout",
                        "Error at 2026-10-20T06:07:08+02:00: timeout", "Error at : timeout"),
                Arguments.of("[2026-10-19 01:02:03,123] Error",
                        "[2026-10-20 06:07:08,456] Error", "[] Error"),
                Arguments.of("Error requestId=123456 trace-id=0123456789abcdef",
                        "Error requestId=789012 trace-id=fedcba9876543210",
                        "Error requestId=<id> trace-id=<id>"),
                Arguments.of("Error 123e4567-e89b-12d3-a456-426614174000",
                        "Error 987e6543-e21b-12d3-a456-426614174999", "Error <uuid>"),
                Arguments.of("Error reading /tmp/run-123/config.yaml",
                        "Error reading /private/tmp/run-456/config.yaml", "Error reading <tmp>/config.yaml"),
                Arguments.of("Error reading /tmp/upload123456.tmp",
                        "Error reading /var/tmp/upload987654.tmp", "Error reading <tmp>/upload<id>.tmp"),
                Arguments.of("Error reading /tmp/cache-abcdef12.tmp",
                        "Error reading /var/tmp/cache-fedcba98.tmp", "Error reading <tmp>/cache-<id>.tmp"),
                Arguments.of("Error reading /var/folders/ab/random/T/run1/config.yaml",
                        "Error reading C:\\Users\\ci\\AppData\\Local\\Temp\\run2\\config.yaml",
                        "Error reading <tmp>/config.yaml"),
                Arguments.of("Error in App$$Lambda$123/0xabcdef12",
                        "Error in App$$Lambda$999/0x12345678", "Error in App$$Lambda$<id>"),
                Arguments.of("Error connecting localhost:49152",
                        "Error connecting localhost:65535", "Error connecting localhost:<port>"),
                Arguments.of("Error connecting [::1]:52000",
                        "Error connecting [::1]:53000", "Error connecting [::1]:<port>"),
                Arguments.of("Error binding ephemeral port 32000",
                        "Error binding ephemeral port 33000", "Error binding ephemeral port <port>"),
                Arguments.of("\u001B[31mError:  timeout\u001B[0m",
                        " Error:\t timeout ", "Error: timeout"));
    }

    @ParameterizedTest
    @MethodSource("equivalentSignatures")
    void normalizesOnlyRecognizedVariableValues(String first, String second, String expected) {
        var fingerprint = service.fingerprint(UNKNOWN, first);
        assertEquals(expected, fingerprint.normalizedSignature());
        assertEquals(fingerprint, service.fingerprint(UNKNOWN, second));
        assertEquals(fingerprint, service.fingerprint(UNKNOWN, fingerprint.normalizedSignature()));
    }

    static Stream<Arguments> distinctSignatures() {
        return Stream.of(
                Arguments.of("Error: connection refused", "Error: connection timed out"),
                Arguments.of("Could not find artifact example:lib:jar:1.0",
                        "Could not find artifact example:lib:jar:2.0"),
                Arguments.of("expected: <200> but was: <500>", "expected: <200> but was: <404>"),
                Arguments.of("App.java:[12,8] cannot find symbol", "Other.java:[12,8] cannot find symbol"),
                Arguments.of("invalid target release: 21", "invalid target release: 25"),
                Arguments.of("Error connecting localhost:5432", "Error connecting localhost:6379"),
                Arguments.of("Error connecting db1:52000", "Error connecting db2:52000"),
                Arguments.of("Error connecting db:52000", "Error connecting db:53000"),
                Arguments.of("Error reading /tmp/run1/config.yaml", "Error reading /tmp/run2/credentials.yaml"),
                Arguments.of("Error reading /tmp/abcdefab.tmp", "Error reading /tmp/cacheabcdefab.tmp"),
                Arguments.of("Error code=123456", "Error code=789012"),
                Arguments.of("Error connecting localhost:65536", "Error connecting localhost:65537"));
    }

    @ParameterizedTest
    @MethodSource("distinctSignatures")
    void preservesRootCauseDiscriminators(String first, String second) {
        assertNotEquals(service.fingerprint(UNKNOWN, first).fingerprint(),
                service.fingerprint(UNKNOWN, second).fingerprint());
    }

    @Test
    void hashesVersionCategoryAndNormalizedSignatureAsUtf8() throws Exception {
        var result = service.fingerprint(UNKNOWN, "Error: timeout");
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("v1\nUNKNOWN\nError: timeout".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, result.fingerprint());
        assertEquals(64, result.fingerprint().length());
        assertNotEquals(result.fingerprint(),
                service.fingerprint(FailureCategory.POSTGRESQL_CONNECTION, "Error: timeout").fingerprint());
    }

    @Test
    void rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> service.fingerprint(null, "Error"));
        assertThrows(IllegalArgumentException.class, () -> service.fingerprint(UNKNOWN, null));
        assertThrows(IllegalArgumentException.class, () -> service.fingerprint(UNKNOWN, " \n"));
        assertThrows(IllegalArgumentException.class,
                () -> service.fingerprint(UNKNOWN, "2026-10-19T01:02:03Z"));
    }
}
