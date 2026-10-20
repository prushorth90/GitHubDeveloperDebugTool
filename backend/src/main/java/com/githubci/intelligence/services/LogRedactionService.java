package com.githubci.intelligence.services;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LogRedactionService {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----.*?"
                    + "(?:-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----|\\z)",
            Pattern.DOTALL);
    private static final Pattern HEADERS = Pattern.compile(
            "(?im)\\b(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key)"
                    + "[\"']?\\s*[:=]\\s*[^\\r\\n]*");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(\\b[\\w.-]*(?:password|passwd|secret|token|credential|api[_-]?key|access[_-]?key)"
                    + "[\\w.-]*[\"']?\\s*[:=]\\s*)"
                    + "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;&\\r\\n]+)");
    private static final Pattern URL_CREDENTIALS = Pattern.compile(
            "(?i)(\\b[a-z][a-z0-9+.-]*://)[^\\s/@]+(?::[^\\s/@]*)?@");
    private static final Pattern SIGNED_QUERY = Pattern.compile(
            "(?i)([?&](?:x-amz-[\\w-]+|x-goog-[\\w-]+|sig|signature|key|auth|"
                    + "[\\w-]*token|[\\w-]*secret|[\\w-]*password)=)[^\\s&#\"']+");
    private static final Pattern TOKENS = Pattern.compile(
            "\\b(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|"
                    + "(?:AKIA|ASIA)[A-Z0-9]{16}|xox[baprs]-[A-Za-z0-9-]+|"
                    + "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\\b");
    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");
    private final List<String> configuredSecrets;

    public LogRedactionService(
            @Value("${github.token:}") String githubToken,
            @Value("${github.webhook-secret:}") String webhookSecret) {
        configuredSecrets = List.of(githubToken, webhookSecret).stream()
                .filter(secret -> !secret.isBlank()).toList();
    }

    public String redact(String logs) {
        String sanitized = ANSI.matcher(Objects.requireNonNull(logs, "Logs are required")).replaceAll("");
        for (String secret : configuredSecrets) {
            sanitized = sanitized.replace(secret, REDACTED);
        }
        sanitized = PRIVATE_KEY.matcher(sanitized).replaceAll(REDACTED);
        sanitized = HEADERS.matcher(sanitized).replaceAll(REDACTED);
        sanitized = AUTH_SCHEME.matcher(sanitized).replaceAll(REDACTED);
        sanitized = URL_CREDENTIALS.matcher(sanitized).replaceAll("$1" + REDACTED + "@");
        sanitized = SIGNED_QUERY.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = ASSIGNMENT.matcher(sanitized).replaceAll("$1" + REDACTED);
        return TOKENS.matcher(sanitized).replaceAll(REDACTED);
    }
}
