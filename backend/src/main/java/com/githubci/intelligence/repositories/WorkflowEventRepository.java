package com.githubci.intelligence.repositories;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.exceptions.AnalysisConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@Repository
public class WorkflowEventRepository {

    private final DynamoDbClient client;
    private final String table;
    private final Clock clock;
    private final Duration lease;

    public WorkflowEventRepository(DynamoDbClient client,
            @Value("${app.dynamodb.table-name}") String table, Clock clock,
            @Value("${app.dynamodb.processing-lease}") Duration lease) {
        if (lease.compareTo(Duration.ofSeconds(30)) < 0) {
            throw new IllegalArgumentException("Workflow event lease must be at least 30 seconds");
        }
        this.client = client;
        this.table = table;
        this.clock = clock;
        this.lease = lease;
    }

    public record Claim(String key, String token, boolean completed) { }

    public Claim claim(WorkflowEvent event) {
        String key = event.idempotencyKey();
        var existing = client.getItem(request -> request.tableName(table).key(key(key))
                .consistentRead(true)).item();
        if (existing.containsKey("status") && "COMPLETED".equals(existing.get("status").s())) {
            return new Claim(key, null, true);
        }
        String token = UUID.randomUUID().toString();
        try {
            client.updateItem(request -> request.tableName(table).key(key(key))
                    .conditionExpression("attribute_not_exists(pk) OR #state = :failed "
                            + "OR (#state = :processing AND leaseUntil <= :now)")
                    .updateExpression("SET #state = :processing, processingToken = :token, "
                            + "leaseUntil = :lease, updatedAt = :now, createdAt = if_not_exists(createdAt, :now), "
                            + "processingAttempts = if_not_exists(processingAttempts, :zero) + :one")
                    .expressionAttributeNames(Map.of("#state", "status"))
                    .expressionAttributeValues(Map.of(
                            ":failed", text("FAILED"), ":processing", text("PROCESSING"),
                            ":token", text(token), ":now", number(clock.millis()),
                            ":lease", number(clock.instant().plus(lease).toEpochMilli()),
                            ":zero", number(0), ":one", number(1))));
        } catch (ConditionalCheckFailedException conflict) {
            throw new AnalysisConflictException();
        }
        return new Claim(key, token, false);
    }

    public void renew(Claim claim) {
        updateOwned(claim, "SET leaseUntil = :lease, updatedAt = :now",
                Map.of(":lease", number(clock.instant().plus(lease).toEpochMilli())));
    }

    public void complete(Claim claim) {
        updateOwned(claim, "SET #state = :terminal, updatedAt = :now REMOVE processingToken, leaseUntil",
                Map.of(":terminal", text("COMPLETED")));
    }

    public void fail(Claim claim) {
        updateOwned(claim, "SET #state = :terminal, updatedAt = :now REMOVE processingToken, leaseUntil",
                Map.of(":terminal", text("FAILED")));
    }

    private void updateOwned(Claim claim, String expression, Map<String, AttributeValue> extra) {
        var values = new java.util.HashMap<>(extra);
        values.put(":processing", text("PROCESSING"));
        values.put(":token", text(claim.token()));
        values.put(":now", number(clock.millis()));
        try {
            client.updateItem(request -> request.tableName(table).key(key(claim.key()))
                    .conditionExpression("#state = :processing AND processingToken = :token AND leaseUntil > :now")
                    .updateExpression(expression).expressionAttributeNames(Map.of("#state", "status"))
                    .expressionAttributeValues(values));
        } catch (ConditionalCheckFailedException staleWorker) {
            throw new AnalysisConflictException();
        }
    }

    private static Map<String, AttributeValue> key(String key) {
        return Map.of("pk", text(key), "sk", text("EVENT"));
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
