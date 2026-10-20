package com.githubci.intelligence.controllers;

import com.githubci.intelligence.dto.GitHubJobs;
import com.githubci.intelligence.dto.GitHubWorkflowRuns;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.services.GitHubService;
import com.githubci.intelligence.services.WorkflowRunAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/github/repositories/{owner}/{repo}")
public class GitHubController {

    private final GitHubService service;
    private final WorkflowRunAnalysisService analysisService;

    public GitHubController(GitHubService service, WorkflowRunAnalysisService analysisService) {
        this.service = service;
        this.analysisService = analysisService;
    }

    @PostMapping("/runs/{runId}/analyze")
    public WorkflowRunAnalysis analyze(
            @PathVariable String owner, @PathVariable String repo, @PathVariable long runId) {
        return analysisService.analyze(owner, repo, runId);
    }

    @GetMapping("/workflows")
    public GitHubWorkflowRuns workflows(
            @PathVariable String owner, @PathVariable String repo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "per_page", defaultValue = "30") int perPage) {
        return service.listWorkflowRuns(owner, repo, page, perPage);
    }

    @GetMapping("/runs/{runId}/jobs")
    public GitHubJobs jobs(
            @PathVariable String owner, @PathVariable String repo, @PathVariable long runId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "per_page", defaultValue = "30") int perPage) {
        return service.listJobs(owner, repo, runId, page, perPage);
    }
}
