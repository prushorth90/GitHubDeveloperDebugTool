package com.githubci.intelligence.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubJob(
        long id,
        @JsonProperty("run_id") long runId,
        String name, String status, String conclusion,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt,
        List<GitHubJobStep> steps) {
}
