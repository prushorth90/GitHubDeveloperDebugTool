package com.githubci.intelligence.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record WorkflowAnalytics(
        Instant from, Instant until, long totalRuns, long failedRuns,
        long recurringFailures, long successfulAnalyses,
        List<DailyRate> dailyRates, Map<FailureCategory, Long> failuresByCategory) {
    public WorkflowAnalytics {
        dailyRates = List.copyOf(dailyRates);
        failuresByCategory = Map.copyOf(failuresByCategory);
    }

    public record DailyRate(LocalDate date, long totalRuns, long failedRuns, Double failureRate) { }
}
