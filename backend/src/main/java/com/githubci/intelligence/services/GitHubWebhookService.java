package com.githubci.intelligence.services;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.exceptions.WebhookException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class GitHubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookService.class);
    private static final Duration DELIVERY_TTL = Duration.ofHours(24);
    private static final int MAX_DELIVERIES = 10_000;
    private static final Set<String> CONCLUSIONS = Set.of(
            "success", "failure", "neutral", "cancelled", "skipped", "timed_out",
            "action_required", "stale", "startup_failure");

    private final String secret;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, Instant> deliveries = new LinkedHashMap<>();

    public GitHubWebhookService(
            @Value("${github.webhook-secret:}") String secret, ObjectMapper mapper, Clock clock) {
        this.secret = secret;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void receive(byte[] body, String signature, String event, String deliveryId) {
        verifySignature(body, signature);
        if (event == null || event.isBlank()) {
            throw malformed();
        }
        if (!"workflow_run".equals(event)) {
            return;
        }

        JsonNode payload;
        try {
            payload = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (JacksonException exception) {
            // Do not attach Jackson's exception: it can include raw payload content.
            throw malformed();
        }
        if (payload == null || !payload.isObject()) {
            throw malformed();
        }
        String action = text(payload.path("action"));
        if (!"completed".equals(action)) {
            return;
        }
        JsonNode run = payload.path("workflow_run");
        if (!"completed".equals(text(run.path("status")))) {
            return;
        }

        JsonNode repository = payload.path("repository");
        String repositoryName = text(repository.path("full_name"));
        String conclusion = text(run.path("conclusion"));
        if (!repositoryName.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
                || !CONCLUSIONS.contains(conclusion)
                || deliveryId == null
                || !deliveryId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            throw malformed();
        }
        long attempt = positiveId(run.path("run_attempt"));
        if (attempt > Integer.MAX_VALUE) {
            throw malformed();
        }
        JsonNode installation = payload.path("installation");
        Long installationId = installation.isMissingNode() || installation.isNull()
                ? null : positiveId(installation.path("id"));
        WorkflowEvent workflowEvent = new WorkflowEvent(
                positiveId(repository.path("id")), repositoryName, installationId,
                positiveId(run.path("id")), (int) attempt, conclusion, deliveryId.toLowerCase(Locale.ROOT));
        logOnce(workflowEvent);
    }

    private void verifySignature(byte[] body, String signature) {
        if (secret.isBlank()) {
            throw new WebhookException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub webhook requires GITHUB_WEBHOOK_SECRET");
        }
        if (signature == null || !signature.matches("sha256=[a-fA-F0-9]{64}")) {
            throw unauthorized();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] supplied = HexFormat.of().parseHex(signature.substring(7));
            if (!MessageDigest.isEqual(mac.doFinal(body), supplied)) {
                throw unauthorized();
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private synchronized void logOnce(WorkflowEvent event) {
        Instant now = clock.instant();
        var iterator = deliveries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isAfter(now)) {
                break;
            }
            iterator.remove();
        }
        if (deliveries.containsKey(event.deliveryId())) {
            return;
        }
        log.info("GitHub workflow completed: repositoryId={}, repositoryName={}, installationId={}, "
                        + "workflowRunId={}, runAttempt={}, conclusion={}, deliveryId={}",
                event.repositoryId(), event.repositoryName(), event.installationId(),
                event.workflowRunId(), event.runAttempt(), event.conclusion(), event.deliveryId());
        if (deliveries.size() >= MAX_DELIVERIES) {
            deliveries.remove(deliveries.keySet().iterator().next());
        }
        deliveries.put(event.deliveryId(), now.plus(DELIVERY_TTL));
    }

    private static String text(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw malformed();
        }
        return node.asText();
    }

    private static long positiveId(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) {
            throw malformed();
        }
        return node.asLong();
    }

    private static WebhookException malformed() {
        return new WebhookException(HttpStatus.BAD_REQUEST, "Invalid GitHub webhook payload or headers");
    }

    private static WebhookException unauthorized() {
        return new WebhookException(HttpStatus.UNAUTHORIZED, "Invalid GitHub webhook signature");
    }
}
