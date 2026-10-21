package com.githubci.intelligence.services;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.githubci.intelligence.dto.FailureCategory;
import com.githubci.intelligence.dto.FailureResponses.Failure;
import com.githubci.intelligence.dto.FailureResponses.JobFailure;
import com.githubci.intelligence.dto.FailureResponses.Page;
import com.githubci.intelligence.dto.FailureResponses.Repository;
import com.githubci.intelligence.dto.FailureResponses.Run;
import com.githubci.intelligence.dto.FailureResponses.Statistics;
import com.githubci.intelligence.dto.GitHubRepository;
import com.githubci.intelligence.exceptions.FailureReadException;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.models.StoredWorkflowAnalysis;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class FailureService {
    public enum Order { asc, desc }

    private final WorkflowAnalysisRepository repository;
    private final ObjectMapper mapper;

    public FailureService(WorkflowAnalysisRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public Page<Repository> repositories(int limit, String token) {
        validatePage(limit, Order.asc);
        var start = cursor(token, "repositories", "REPOSITORIES", false, false);
        return read(() -> {
            var page = repository.repositories(limit, start);
            return new Page<>(page.items().stream().map(this::repositoryDto).toList(),
                    token("repositories", page.nextKey()));
        });
    }

    public Page<Run> runs(long repositoryId, boolean failuresOnly, FailureCategory category,
                          Order order, int limit, String token) {
        validatePage(limit, order);
        String scope = repositoryId + ":" + failuresOnly + ":" + category + ":" + order;
        return read(() -> {
            var repo = requireRepository(repositoryId);
            var start = cursor(token, scope, partition(repo), true, failuresOnly);
            var page = repository.runs(repo.fullName(), failuresOnly, order == Order.asc, limit, start);
            return new Page<>(page.items().stream().filter(record -> matches(record, category))
                    .map(this::runDto).toList(), token(scope, page.nextKey()));
        });
    }

    public Failure failure(long repositoryId, long runId, Integer attempt) {
        validateRun(runId, attempt);
        return read(() -> failureDto(requireFailure(requireRepository(repositoryId), runId, attempt)));
    }

    public Page<Run> similar(long repositoryId, long runId, Integer attempt, FailureCategory category,
                             Order order, int limit, String token) {
        validatePage(limit, order);
        validateRun(runId, attempt);
        return read(() -> {
            var repo = requireRepository(repositoryId);
            var source = requireFailure(repo, runId, attempt);
            Set<String> fingerprints = fingerprints(source);
            if (fingerprints.isEmpty()) {
                throw new FailureReadException(HttpStatus.CONFLICT,
                        "This failure has no available fingerprints for similarity matching");
            }
            String scope = repositoryId + ":similar:" + runId + ":" + source.workflowRun().runAttempt()
                    + ":" + category + ":" + order;
            var start = cursor(token, scope, partition(repo), true, true);
            var page = repository.runs(repo.fullName(), true, order == Order.asc, limit, start);
            return new Page<>(page.items().stream()
                    .filter(record -> record.workflowRun().id() != runId)
                    .filter(record -> matches(record, category))
                    .filter(record -> fingerprints(record).stream().anyMatch(fingerprints::contains))
                    .map(this::runDto).toList(), token(scope, page.nextKey()));
        });
    }

    public Statistics statistics(long repositoryId) {
        return read(() -> {
            var repo = requireRepository(repositoryId);
            long total = 0, completed = 0, processing = 0, failed = 0, failures = 0, unavailable = 0;
            var categories = new EnumMap<FailureCategory, Long>(FailureCategory.class);
            Run latestRun = null;
            Instant lastAnalyzedAt = null;
            for (FailureCategory category : FailureCategory.values()) {
                categories.put(category, 0L);
            }
            Map<String, AttributeValue> start = Map.of();
            do {
                var page = repository.runs(repo.fullName(), false, false, 100, start);
                for (var record : page.items()) {
                    if (latestRun == null) {
                        latestRun = runDto(record);
                    }
                    if (record.status() == AnalysisStatus.COMPLETED && record.updatedAt() != null
                            && (lastAnalyzedAt == null || record.updatedAt().isAfter(lastAnalyzedAt))) {
                        lastAnalyzedAt = record.updatedAt();
                    }
                    total++;
                    switch (record.status()) {
                        case PROCESSING -> processing++;
                        case FAILED -> failed++;
                        case COMPLETED -> {
                            completed++;
                            requireCompletedPayload(record);
                            if (!record.analysis().failedJobs().isEmpty()) {
                                failures++;
                            }
                            for (var job : record.analysis().failedJobs()) {
                                if (job.analysis() == null) {
                                    unavailable++;
                                } else {
                                    categories.merge(job.analysis().category(), 1L, Long::sum);
                                }
                            }
                        }
                    }
                }
                start = page.nextKey();
            } while (!start.isEmpty());
            return new Statistics(total, completed, processing, failed, failures, unavailable, categories,
                    latestRun, lastAnalyzedAt);
        });
    }

    private GitHubRepository requireRepository(long id) {
        positive(id, "Repository ID");
        return repository.repository(id).orElseThrow(() ->
                new FailureReadException(HttpStatus.NOT_FOUND, "Repository not found"));
    }

    private StoredWorkflowAnalysis requireFailure(GitHubRepository repo, long runId, Integer attempt) {
        var record = (attempt == null ? repository.latestRun(repo.fullName(), runId)
                : repository.findRun(repo.fullName(), runId, attempt))
                .orElseThrow(() -> new FailureReadException(HttpStatus.NOT_FOUND, "Workflow run not found"));
        if (record.status() != AnalysisStatus.COMPLETED) {
            throw new FailureReadException(HttpStatus.CONFLICT,
                    record.status() == AnalysisStatus.PROCESSING
                            ? "Workflow analysis is still processing" : "Workflow analysis failed; retry analysis");
        }
        requireCompletedPayload(record);
        if (record.analysis().failedJobs().isEmpty()) {
            throw new FailureReadException(HttpStatus.NOT_FOUND, "Workflow run has no failed jobs");
        }
        return record;
    }

    private Repository repositoryDto(GitHubRepository repo) {
        return new Repository(repo.id(), repo.name(), repo.fullName(), repo.htmlUrl(), repo.privateRepository());
    }

    private Run runDto(StoredWorkflowAnalysis record) {
        if (record.status() == AnalysisStatus.COMPLETED) {
            requireCompletedPayload(record);
        }
        var run = record.workflowRun();
        var categories = record.analysis() == null ? List.<FailureCategory>of() :
                record.analysis().failedJobs().stream().filter(job -> job.analysis() != null)
                        .map(job -> job.analysis().category()).distinct().sorted().toList();
        boolean complete = record.status() == AnalysisStatus.COMPLETED
                && record.analysis().failedJobs().stream().allMatch(job -> job.analysis() != null);
        return new Run(run.id(), run.runAttempt(), run.name(), run.headBranch(), run.conclusion(),
                run.htmlUrl(), run.createdAt() == null ? record.createdAt() : run.createdAt(),
                record.status() == AnalysisStatus.COMPLETED ? record.updatedAt() : null,
                record.status(), complete, categories);
    }

    private Failure failureDto(StoredWorkflowAnalysis record) {
        return new Failure(runDto(record), record.analysis().failedJobs().stream()
                .map(job -> new JobFailure(job.job().id(), job.job().name(), job.logStatus(),
                        job.logMessage(), job.analysis())).toList());
    }

    private boolean matches(StoredWorkflowAnalysis record, FailureCategory category) {
        if (record.status() == AnalysisStatus.COMPLETED) {
            requireCompletedPayload(record);
        }
        return category == null || record.analysis() != null && record.analysis().failedJobs().stream()
                .anyMatch(job -> job.analysis() != null && job.analysis().category() == category);
    }

    private Set<String> fingerprints(StoredWorkflowAnalysis record) {
        requireCompletedPayload(record);
        return record.analysis().failedJobs().stream().filter(job -> job.analysis() != null)
                .map(job -> job.analysis().fingerprint()).filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
    }

    private void requireCompletedPayload(StoredWorkflowAnalysis record) {
        if (record.analysis() == null) {
            throw new FailureReadException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored analysis is incomplete");
        }
    }

    private void validatePage(int limit, Order order) {
        if (limit < 1 || limit > 100 || order == null) {
            throw new FailureReadException(HttpStatus.BAD_REQUEST, "Limit must be 1-100 and order must be asc or desc");
        }
    }

    private void positive(long value, String name) {
        if (value <= 0) {
            throw new FailureReadException(HttpStatus.BAD_REQUEST, name + " must be positive");
        }
    }

    private void validateRun(long runId, Integer attempt) {
        positive(runId, "Run ID");
        if (attempt != null) {
            positive(attempt, "Attempt");
        }
    }

    private String partition(GitHubRepository repo) {
        return "REPO#" + repo.fullName().toLowerCase(java.util.Locale.ROOT);
    }

    public record Cursor(String scope, Map<String, String> key) { }

    private String token(String scope, Map<String, AttributeValue> key) {
        if (key.isEmpty()) {
            return null;
        }
        var values = key.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().s()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mapper.writeValueAsString(new Cursor(scope, values)).getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, AttributeValue> cursor(
            String token, String scope, String partition, boolean indexed, boolean failures) {
        if (token == null) {
            return Map.of();
        }
        try {
            if (token.isBlank() || token.length() > 4096) {
                throw new IllegalArgumentException();
            }
            var cursor = mapper.readValue(Base64.getUrlDecoder().decode(token), Cursor.class);
            Set<String> keys = !indexed ? Set.of("pk", "sk") :
                    failures ? Set.of("pk", "sk", "failurePk", "failureSk") : Set.of("pk", "sk", "runSk");
            if (cursor == null || !scope.equals(cursor.scope()) || cursor.key() == null || !cursor.key().keySet().equals(keys)
                    || !partition.equals(cursor.key().get("pk"))
                    || cursor.key().values().stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 1024)
                    || indexed && !cursor.key().get("sk").matches("RUN#[0-9]{20}#ATTEMPT#[0-9]{10}")
                    || !indexed && !cursor.key().get("sk").matches("REPOSITORY#[0-9]{20}")
                    || failures && !partition.equals(cursor.key().get("failurePk"))) {
                throw new IllegalArgumentException();
            }
            if (indexed) {
                String chronology = cursor.key().get(failures ? "failureSk" : "runSk");
                if (!chronology.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z#"
                        + cursor.key().get("sk"))) {
                    throw new IllegalArgumentException();
                }
                java.time.Instant.parse(chronology.substring(0, 24));
            }
            return cursor.key().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                    entry -> AttributeValue.builder().s(entry.getValue()).build()));
        } catch (IllegalArgumentException | JacksonException | java.time.DateTimeException exception) {
            throw new FailureReadException(HttpStatus.BAD_REQUEST, "Invalid pagination token for this query");
        }
    }

    private <T> T read(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (SdkException exception) {
            throw new FailureReadException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Analysis database is temporarily unavailable", exception);
        } catch (JacksonException exception) {
            throw new FailureReadException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored analysis could not be read", exception);
        }
    }
}
