package com.githubci.intelligence.services;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class LogRedactionServiceTest {

    private final LogRedactionService redactor = new LogRedactionService("configured-secret", "webhook-secret");

    @ParameterizedTest
    @ValueSource(strings = {
            "Authorization: Bearer sensitive-value",
            "\"Authorization\": \"Basic sensitive-value\"",
            "proxy-authorization=Basic sensitive-value",
            "Cookie: session=sensitive-value",
            "Set-Cookie: session=sensitive-value; HttpOnly",
            "X-Api-Key: sensitive-value",
            "PASSWORD=sensitive-value",
            "\"client_secret\": \"sensitive-value with spaces\"",
            "aws_secret_access_key='sensitive-value'",
            "https://user:sensitive-value@example.com/path",
            "https://example.com/log?X-Amz-Signature=sensitive-value&ok=yes",
            "https://example.com/?access_token=sensitive-value",
            "Bearer sensitive-value",
            "-----BEGIN RSA PRIVATE KEY-----\nsensitive-value\n-----END RSA PRIVATE KEY-----",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nsensitive-value"
    })
    void removesHeadersAndCommonCredentialValues(String logs) {
        String sanitized = redactor.redact(logs);
        assertFalse(sanitized.contains("sensitive-value"));
        assertTrue(sanitized.contains("[REDACTED]"));
        assertEquals(sanitized, redactor.redact(sanitized));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ghp_abcdefghijklmnopqrstuvwxyz1234567890",
            "github_pat_abcdefghijklmnopqrstuvwxyz_1234567890",
            "AKIAIOSFODNN7EXAMPLE",
            "ASIAIOSFODNN7EXAMPLE",
            "xoxb-123456789-123456789-abcdefghijkl",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature",
            "configured-secret",
            "webhook-secret"
    })
    void removesStandaloneTokensAndConfiguredSecrets(String secret) {
        assertEquals("error [REDACTED]", redactor.redact("error " + secret));
    }

    @Test
    void handlesLargeUnbrokenLogLinesWithoutQuadraticMatching() {
        String logs = "[ERROR] " + "x".repeat(400_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals(logs, redactor.redact(logs)));
    }

    @Test
    void preservesOrdinaryLogsAndLineNumbersAndStripsAnsi() {
        String log = "2026-10-17T03:00:00Z [ERROR] cannot find symbol\nTests run: 5, Failures: 1\n";
        assertEquals(log, redactor.redact(log));
        assertEquals("password=[REDACTED]\n[ERROR] failed",
                redactor.redact("pass\u001B[31mword=hidden\n[ERROR] failed"));
        assertEquals("", redactor.redact(""));
        assertThrows(NullPointerException.class, () -> redactor.redact(null));
    }
}
