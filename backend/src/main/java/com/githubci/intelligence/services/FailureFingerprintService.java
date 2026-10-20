package com.githubci.intelligence.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.githubci.intelligence.dto.FailureCategory;
import org.springframework.stereotype.Service;

@Service
public class FailureFingerprintService {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?<!\\w)\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}"
                    + "(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?(?!\\w)");
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern GENERATED_ID = Pattern.compile(
            "(?i)(\\b(?:request|correlation|trace|span|run|build)[-_ ]?id\\s*[:=]\\s*)"
                    + "(?:[0-9a-f]{16,64}|\\d+)(?![\\w.-])");
    private static final Pattern TEMP_PATH = Pattern.compile(
            "(?i)(?<![\\w/])(?:/private)?/(?:tmp|var/tmp)/[^\\s\"'<>]+"
                    + "|/var/folders/[^\\s/\"'<>]+/[^\\s/\"'<>]+/[TC]/[^\\s\"'<>]+"
                    + "|[a-z]:\\\\(?:Users\\\\[^\\\\\\s]+\\\\AppData\\\\Local\\\\Temp|Windows\\\\Temp)"
                    + "\\\\[^\\s\"'<>]+");
    private static final Pattern TEMP_FILE_SUFFIX = Pattern.compile(
            "(?:(?<![a-zA-Z0-9])[0-9a-fA-F]{8,}|\\d{4,})(?=\\.[^.]+$|$)");
    private static final Pattern GENERATED_CLASS = Pattern.compile(
            "(\\$\\$Lambda\\$)\\d+(?:/0x[0-9a-fA-F]+)?");
    private static final Pattern LOOPBACK_PORT = Pattern.compile(
            "(?i)(\\b(?:localhost|127\\.0\\.0\\.1)|\\[::1\\])"
                    + ":(\\d{1,5})(?![\\w\\d])");
    private static final Pattern EPHEMERAL_PORT = Pattern.compile(
            "(?i)(\\bephemeral\\s+port\\s*[:=]?\\s*)(\\d{1,5})(?![\\w\\d])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public Fingerprint fingerprint(FailureCategory category, String errorSignature) {
        if (category == null || errorSignature == null || errorSignature.isBlank()) {
            throw new IllegalArgumentException("Failure category and nonblank error signature are required");
        }
        String normalized = ANSI.matcher(errorSignature).replaceAll("");
        // Actions timestamps were already stripped by the analyzer; remove embedded timestamps too.
        normalized = TIMESTAMP.matcher(normalized).replaceAll("");
        normalized = UUID.matcher(normalized).replaceAll("<uuid>");
        normalized = GENERATED_ID.matcher(normalized).replaceAll("$1<id>");
        normalized = TEMP_PATH.matcher(normalized).replaceAll(match -> {
            String path = match.group().replace('\\', '/');
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            return Matcher.quoteReplacement("<tmp>/"
                    + TEMP_FILE_SUFFIX.matcher(fileName).replaceAll("<id>"));
        });
        normalized = GENERATED_CLASS.matcher(normalized).replaceAll("$1<id>");
        normalized = LOOPBACK_PORT.matcher(normalized).replaceAll(match -> {
            int port = Integer.parseInt(match.group(2));
            return match.group(1) + ":" + (port >= 49152 && port <= 65535 ? "<port>" : match.group(2));
        });
        normalized = EPHEMERAL_PORT.matcher(normalized).replaceAll(match -> {
            int port = Integer.parseInt(match.group(2));
            return match.group(1) + (port > 0 && port <= 65535 ? "<port>" : match.group(2));
        });
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Error signature must contain more than timestamps or ANSI codes");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    ("v1\n" + category.name() + "\n" + normalized).getBytes(StandardCharsets.UTF_8));
            return new Fingerprint(normalized, HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record Fingerprint(String normalizedSignature, String fingerprint) {
    }
}
