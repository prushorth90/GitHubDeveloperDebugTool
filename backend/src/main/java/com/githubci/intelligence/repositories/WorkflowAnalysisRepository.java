package com.githubci.intelligence.repositories;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.exceptions.AnalysisConflictException;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.models.StoredWorkflowAnalysis;
import com.githubci.intelligence.models.WorkflowAnalysisItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class WorkflowAnalysisRepository {

    public static final String RECENT_FAILURES_INDEX = "recent-failures";
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final int MAX_PAYLOAD_BYTES = 350 * 1024;
    private final DynamoDbTable<WorkflowAnalysisItem> table;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration lease;

    public WorkflowAnalysisRepository(
            DynamoDbEnhancedClient client, ObjectMapper mapper, Clock clock,
            @Value("${app.dynamodb.table-name}") String tableName,
            @Value("${app.dynamodb.processing-lease}") Duration lease) {
        if (lease.toMillis() < 1) {
            throw new IllegalArgumentException("DynamoDB processing lease must be positive");
        }
        this.table = client.table(tableName, TableSchema.fromBean(WorkflowAnalysisItem.class));
        this.mapper = mapper;
        this.clock = clock;
        this.lease = lease;
    }

    public record Claim(StoredWorkflowAnalysis record, String token) {
        public boolean completed() { return record.status() == AnalysisStatus.COMPLETED; }
    }

    public Claim claim(String repository, GitHubWorkflowRun run) {
        validateRun(run);
        WorkflowAnalysisItem item = newItem(repository, run);
        try {
            put(item, Expression.builder().expression("attribute_not_exists(pk)").build());
            return new Claim(decode(item), item.getProcessingToken());
        } catch (ConditionalCheckFailedException duplicate) {
            WorkflowAnalysisItem existing = table.getItem(request -> request
                    .key(key(repository, run.id(), run.runAttempt())).consistentRead(true));
            if (existing == null) {
                throw new AnalysisConflictException();
            }
            if (AnalysisStatus.COMPLETED.name().equals(existing.getStatus())) {
                return new Claim(decode(existing), null);
            }
            item.setCreatedAt(existing.getCreatedAt());
            Expression retry = Expression.builder()
                    .expression("#state = :failed OR (#state = :processing AND leaseUntil <= :now)")
                    .expressionNames(Map.of("#state", "status"))
                    .expressionValues(Map.of(":failed", value("FAILED"), ":processing", value("PROCESSING"),
                            ":now", value(item.getUpdatedAt())))
                    .build();
            try {
                put(item, retry);
            } catch (ConditionalCheckFailedException conflict) {
                throw new AnalysisConflictException();
            }
            return new Claim(decode(item), item.getProcessingToken());
        }
    }

    public void complete(Claim claim, WorkflowRunAnalysis analysis) {
        StoredWorkflowAnalysis record = claim.record();
        if (analysis.runId() != record.workflowRun().id()
                || !analysis.repository().equalsIgnoreCase(record.repository())) {
            throw new IllegalArgumentException("Analysis does not match its claimed workflow run");
        }
        WorkflowAnalysisItem item = terminalItem(claim, AnalysisStatus.COMPLETED);
        item.setAnalysis(mapper.writeValueAsString(analysis));
        if (!analysis.failedJobs().isEmpty()) {
            item.setFailurePk(item.getPk());
            Instant occurredAt = record.workflowRun().createdAt();
            item.setFailureSk(TIMESTAMP.format(occurredAt == null ? record.createdAt() : occurredAt)
                    + "#" + item.getSk());
        }
        try {
            put(item, ownsLease(claim));
        } catch (ConditionalCheckFailedException conflict) {
            throw new AnalysisConflictException();
        }
    }

    public boolean fail(Claim claim) {
        try {
            put(terminalItem(claim, AnalysisStatus.FAILED), ownsLease(claim));
            return true;
        } catch (ConditionalCheckFailedException staleWorker) {
            return false;
        }
    }

    public void renew(Claim claim) {
        try {
            table.updateItem(request -> request
                    .item(leaseItem(claim))
                    .ignoreNulls(true)
                    .conditionExpression(ownsLease(claim)));
        } catch (ConditionalCheckFailedException staleWorker) {
            throw new AnalysisConflictException();
        }
    }

    private WorkflowAnalysisItem leaseItem(Claim claim) {
        WorkflowAnalysisItem item = new WorkflowAnalysisItem();
        item.setPk(partition(claim.record().repository()));
        item.setSk(sort(claim.record().workflowRun().id(), claim.record().workflowRun().runAttempt()));
        item.setLeaseUntil(TIMESTAMP.format(clock.instant().plus(lease)));
        item.setUpdatedAt(TIMESTAMP.format(clock.instant()));
        return item;
    }

    public Optional<StoredWorkflowAnalysis> findRun(String repository, long runId, int attempt) {
        return Optional.ofNullable(table.getItem(request -> request
                        .key(key(repository, runId, attempt)).consistentRead(true)))
                .map(this::decode);
    }

    public List<StoredWorkflowAnalysis> recentFailures(String repository, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Recent failures limit must be between 1 and 100");
        }
        return table.index(RECENT_FAILURES_INDEX).query(request -> request
                        .queryConditional(QueryConditional.keyEqualTo(
                                Key.builder().partitionValue(partition(repository)).build()))
                        .scanIndexForward(false).limit(limit))
                .stream().flatMap(page -> page.items().stream()).limit(limit).map(this::decode).toList();
    }

    private WorkflowAnalysisItem newItem(String repository, GitHubWorkflowRun run) {
        WorkflowAnalysisItem item = new WorkflowAnalysisItem();
        item.setPk(partition(repository));
        item.setSk(sort(run.id(), run.runAttempt()));
        item.setRepository(repository);
        item.setWorkflowRun(mapper.writeValueAsString(run));
        item.setStatus(AnalysisStatus.PROCESSING.name());
        item.setProcessingToken(UUID.randomUUID().toString());
        item.setCreatedAt(TIMESTAMP.format(clock.instant()));
        item.setUpdatedAt(item.getCreatedAt());
        item.setLeaseUntil(TIMESTAMP.format(clock.instant().plus(lease)));
        return item;
    }

    private WorkflowAnalysisItem terminalItem(Claim claim, AnalysisStatus status) {
        if (claim.completed() || claim.token() == null) {
            throw new IllegalArgumentException("A processing claim is required");
        }
        WorkflowAnalysisItem item = newItem(claim.record().repository(), claim.record().workflowRun());
        item.setCreatedAt(TIMESTAMP.format(claim.record().createdAt()));
        item.setStatus(status.name());
        item.setProcessingToken(null);
        item.setLeaseUntil(null);
        return item;
    }

    private Expression ownsLease(Claim claim) {
        return Expression.builder()
                .expression("#state = :processing AND processingToken = :token AND leaseUntil > :now")
                .expressionNames(Map.of("#state", "status"))
                .expressionValues(Map.of(":processing", value("PROCESSING"), ":token", value(claim.token()),
                        ":now", value(TIMESTAMP.format(clock.instant()))))
                .build();
    }

    private void put(WorkflowAnalysisItem item, Expression condition) {
        int payloadBytes = item.getWorkflowRun().getBytes(StandardCharsets.UTF_8).length
                + (item.getAnalysis() == null ? 0 : item.getAnalysis().getBytes(StandardCharsets.UTF_8).length);
        if (payloadBytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Workflow analysis exceeds the 350 KiB persistence payload limit");
        }
        table.putItem(request -> request.item(item).conditionExpression(condition));
    }

    private StoredWorkflowAnalysis decode(WorkflowAnalysisItem item) {
        return new StoredWorkflowAnalysis(item.getRepository(),
                mapper.readValue(item.getWorkflowRun(), GitHubWorkflowRun.class),
                AnalysisStatus.valueOf(item.getStatus()),
                item.getAnalysis() == null ? null : mapper.readValue(item.getAnalysis(), WorkflowRunAnalysis.class),
                Instant.parse(item.getCreatedAt()), Instant.parse(item.getUpdatedAt()),
                item.getLeaseUntil() == null ? null : Instant.parse(item.getLeaseUntil()));
    }

    private static Key key(String repository, long runId, int attempt) {
        return Key.builder().partitionValue(partition(repository)).sortValue(sort(runId, attempt)).build();
    }

    private static String partition(String repository) {
        if (repository == null
                || !repository.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")) {
            throw new IllegalArgumentException("Invalid repository name");
        }
        return "REPO#" + repository.toLowerCase(Locale.ROOT);
    }

    private static String sort(long runId, int attempt) {
        if (runId <= 0 || attempt <= 0) {
            throw new IllegalArgumentException("Run ID and attempt must be positive");
        }
        return String.format(Locale.ROOT, "RUN#%020d#ATTEMPT#%010d", runId, attempt);
    }

    private static void validateRun(GitHubWorkflowRun run) {
        if (run == null || run.runAttempt() == null || !"completed".equals(run.status())) {
            throw new IllegalArgumentException("A completed workflow run with an attempt is required");
        }
        sort(run.id(), run.runAttempt());
    }

    private static AttributeValue value(String text) {
        return AttributeValue.builder().s(text).build();
    }
}
