package com.githubci.intelligence.controllers;

import com.githubci.intelligence.dto.FailureCategory;
import com.githubci.intelligence.dto.FailureResponses.ApiResponse;
import com.githubci.intelligence.dto.FailureResponses.Failure;
import com.githubci.intelligence.dto.FailureResponses.Page;
import com.githubci.intelligence.dto.FailureResponses.Repository;
import com.githubci.intelligence.dto.FailureResponses.Run;
import com.githubci.intelligence.dto.FailureResponses.Statistics;
import com.githubci.intelligence.services.FailureService;
import com.githubci.intelligence.services.FailureService.Order;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final FailureService service;

    public RepositoryController(FailureService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Page<Repository>> repositories(
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String nextToken) {
        return new ApiResponse<>(service.repositories(limit, nextToken));
    }

    @GetMapping("/{repositoryId}/runs")
    public ApiResponse<Page<Run>> runs(
            @PathVariable long repositoryId, @RequestParam(required = false) FailureCategory category,
            @RequestParam(defaultValue = "desc") Order order, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String nextToken) {
        return new ApiResponse<>(service.runs(repositoryId, false, category, order, limit, nextToken));
    }

    @GetMapping("/{repositoryId}/failures")
    public ApiResponse<Page<Run>> failures(
            @PathVariable long repositoryId, @RequestParam(required = false) FailureCategory category,
            @RequestParam(defaultValue = "desc") Order order, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String nextToken) {
        return new ApiResponse<>(service.runs(repositoryId, true, category, order, limit, nextToken));
    }

    @GetMapping("/{repositoryId}/failures/{runId}")
    public ApiResponse<Failure> failure(
            @PathVariable long repositoryId, @PathVariable long runId, @RequestParam(required = false) Integer attempt) {
        return new ApiResponse<>(service.failure(repositoryId, runId, attempt));
    }

    @GetMapping("/{repositoryId}/statistics")
    public ApiResponse<Statistics> statistics(@PathVariable long repositoryId) {
        return new ApiResponse<>(service.statistics(repositoryId));
    }

    @GetMapping("/{repositoryId}/failures/{runId}/similar")
    public ApiResponse<Page<Run>> similar(
            @PathVariable long repositoryId, @PathVariable long runId, @RequestParam(required = false) Integer attempt,
            @RequestParam(required = false) FailureCategory category, @RequestParam(defaultValue = "desc") Order order,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String nextToken) {
        return new ApiResponse<>(service.similar(repositoryId, runId, attempt, category, order, limit, nextToken));
    }
}
