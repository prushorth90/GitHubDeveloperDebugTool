package com.githubci.intelligence.controllers;

import com.githubci.intelligence.dto.FailureResponses.ApiResponse;
import com.githubci.intelligence.dto.WorkflowAnalytics;
import com.githubci.intelligence.services.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyticsController {
    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/api/repositories/{repositoryId}/analytics")
    public ApiResponse<WorkflowAnalytics> analytics(
            @PathVariable long repositoryId, @RequestParam(defaultValue = "7") int days) {
        return new ApiResponse<>(service.analytics(repositoryId, days));
    }
}
