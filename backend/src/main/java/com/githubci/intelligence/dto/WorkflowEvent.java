package com.githubci.intelligence.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;

public record WorkflowEvent(
        @JsonProperty("repository_id") long repositoryId,
        @JsonProperty("repository_name") String repositoryName,
        @JsonProperty("installation_id") Long installationId,
        @JsonProperty("workflow_run_id") long workflowRunId,
        @JsonProperty("run_attempt") int runAttempt,
        String conclusion,
        @JsonProperty("delivery_id") String deliveryId) {

    public static final Set<String> FAILED_CONCLUSIONS = Set.of("failure", "timed_out", "startup_failure");
    public static final Set<String> COMPLETED_CONCLUSIONS = Set.of(
            "success", "failure", "neutral", "cancelled", "skipped", "timed_out",
            "action_required", "stale", "startup_failure");

    public void validate() {
        if (repositoryId <= 0 || workflowRunId <= 0 || runAttempt <= 0
                || repositoryName == null
                || !repositoryName.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
                || (installationId != null && installationId <= 0)
                || !COMPLETED_CONCLUSIONS.contains(conclusion == null ? "" : conclusion)
                || deliveryId == null
                || !deliveryId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            throw new IllegalArgumentException("Invalid completed workflow event");
        }
    }

    public String idempotencyKey() {
        validate();
        return "REPOSITORY#" + repositoryId + "#RUN#" + workflowRunId + "#ATTEMPT#" + runAttempt;
    }
}
