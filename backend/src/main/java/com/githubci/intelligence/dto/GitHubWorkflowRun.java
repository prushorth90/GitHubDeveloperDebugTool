package com.githubci.intelligence.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubWorkflowRun(
        long id, String name,
        @JsonProperty("workflow_id") long workflowId,
        @JsonProperty("run_number") long runNumber,
        @JsonProperty("run_attempt") Integer runAttempt,
        String event, String status, String conclusion,
        @JsonProperty("head_branch") String headBranch,
        @JsonProperty("head_sha") String headSha,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        GitHubRepository repository) {
}
