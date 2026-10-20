package com.githubci.intelligence.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubJobs(@JsonProperty("total_count") long totalCount, List<GitHubJob> jobs) {
}
