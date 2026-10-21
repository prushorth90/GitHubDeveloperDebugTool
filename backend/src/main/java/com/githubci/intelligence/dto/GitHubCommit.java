package com.githubci.intelligence.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCommit(List<FileChange> files) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileChange(String filename, String status, String patch) { }
}
