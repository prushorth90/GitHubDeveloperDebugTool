package com.githubci.intelligence.services;

import java.util.ArrayList;
import java.util.List;

import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubJobs;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.dto.WorkflowRunAnalysis.FailedJobAnalysis;
import com.githubci.intelligence.exceptions.GitHubException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import static com.githubci.intelligence.dto.WorkflowRunAnalysis.LogStatus.AVAILABLE;
import static com.githubci.intelligence.dto.WorkflowRunAnalysis.LogStatus.UNAVAILABLE;

@Service
public class WorkflowRunAnalysisService {

    private static final int PAGE_SIZE = 100;
    private final GitHubService github;
    private final FailureAnalyzerService analyzer;

    public WorkflowRunAnalysisService(GitHubService github, FailureAnalyzerService analyzer) {
        this.github = github;
        this.analyzer = analyzer;
    }

    public WorkflowRunAnalysis analyze(String owner, String repo, long runId) {
        List<FailedJobAnalysis> failures = new ArrayList<>();
        long retrieved = 0;
        for (int page = 1; ; page++) {
            GitHubJobs jobs = github.listJobs(owner, repo, runId, page, PAGE_SIZE);
            for (GitHubJob job : jobs.jobs()) {
                if (job == null || job.id() <= 0 || job.runId() != runId) {
                    throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned an invalid job");
                }
                if ("failure".equals(job.conclusion()) || "timed_out".equals(job.conclusion())) {
                    failures.add(analyzeJob(owner, repo, job));
                }
            }
            retrieved += jobs.jobs().size();
            if (retrieved >= jobs.totalCount()) {
                break;
            }
            if (jobs.jobs().isEmpty()) {
                throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned an incomplete jobs response");
            }
        }
        return new WorkflowRunAnalysis(owner + "/" + repo, runId, failures);
    }

    private FailedJobAnalysis analyzeJob(String owner, String repo, GitHubJob job) {
        String logs;
        try {
            logs = github.getFailedJobLogs(owner, repo, job.id());
        } catch (GitHubException exception) {
            if (exception.getStatus() != HttpStatus.GONE) {
                throw exception;
            }
            return new FailedJobAnalysis(job, UNAVAILABLE, exception.getMessage(), null);
        }
        if (logs.isBlank()) {
            return new FailedJobAnalysis(job, UNAVAILABLE, "GitHub job logs are empty", null);
        }
        return new FailedJobAnalysis(job, AVAILABLE, null, analyzer.analyze(logs));
    }
}
