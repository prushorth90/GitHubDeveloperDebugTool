package com.githubci.intelligence.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WorkflowEvent(
        @JsonProperty("repository_id") long repositoryId,
        @JsonProperty("repository_name") String repositoryName,
        @JsonProperty("installation_id") Long installationId,
        @JsonProperty("workflow_run_id") long workflowRunId,
        @JsonProperty("run_attempt") int runAttempt,
        String conclusion,
        @JsonProperty("delivery_id") String deliveryId) {
}
