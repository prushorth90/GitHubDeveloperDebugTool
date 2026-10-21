package com.githubci.intelligence.services;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.githubci.intelligence.dto.FailureCategory;
import com.githubci.intelligence.dto.WorkflowAnalytics;
import com.githubci.intelligence.dto.WorkflowAnalytics.DailyRate;
import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.exceptions.FailureReadException;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.core.JacksonException;
// analytic service
@Service
public class AnalyticsService {
    private final WorkflowAnalysisRepository repository;
    private final Clock clock;

    public AnalyticsService(WorkflowAnalysisRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public WorkflowAnalytics analytics(long repositoryId, int days) {
        if (repositoryId <= 0 || !Set.of(7, 30, 90).contains(days)) {
            throw new FailureReadException(HttpStatus.BAD_REQUEST,
                    "Repository ID must be positive and days must be 7, 30 or 90");
        }
        try {
            return aggregate(repositoryId, days);
        } catch (SdkException exception) {
            throw new FailureReadException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Analysis database is temporarily unavailable", exception);
        } catch (JacksonException exception) {
            throw new FailureReadException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored analysis could not be read", exception);
        }
    }

    private WorkflowAnalytics aggregate(long repositoryId, int days) {
        var repo = repository.repository(repositoryId).orElseThrow(() ->
                new FailureReadException(HttpStatus.NOT_FOUND, "Repository not found"));
        var until = clock.instant();
        var today = until.atZone(ZoneOffset.UTC).toLocalDate();
        var firstDay = today.minusDays(days - 1L);
        var from = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        var daily = new LinkedHashMap<java.time.LocalDate, long[]>();
        for (int i = 0; i < days; i++) {
            daily.put(firstDay.plusDays(i), new long[2]);
        }
        var categories = new EnumMap<FailureCategory, Long>(FailureCategory.class);
        for (var category : FailureCategory.values()) {
            categories.put(category, 0L);
        }
        long total = 0, failed = 0, recurring = 0, analyzed = 0;
        var seen = new HashSet<String>();
        Map<String, AttributeValue> start = Map.of();
        do {
            var page = repository.runsBetween(repo.fullName(), from, until, start);
            for (var record : page.items()) {
                var run = record.workflowRun();
                var occurred = run.createdAt() == null ? record.createdAt() : run.createdAt();
                if (occurred.isBefore(from) || !occurred.isBefore(until)) {
                    continue;
                }
                var counts = daily.get(occurred.atZone(ZoneOffset.UTC).toLocalDate());
                total++;
                counts[0]++;
                if (!WorkflowEvent.FAILED_CONCLUSIONS.contains(run.conclusion())) {
                    continue;
                }
                failed++;
                counts[1]++;
                if (record.status() != AnalysisStatus.COMPLETED) {
                    continue;
                }
                if (record.analysis() == null) {
                    throw new FailureReadException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored analysis is incomplete");
                }
                var jobs = record.analysis().failedJobs();
                if (!jobs.isEmpty() && jobs.stream().allMatch(job -> job.analysis() != null)) {
                    analyzed++;
                }
                jobs.stream().filter(job -> job.analysis() != null)
                        .map(job -> job.analysis().category()).distinct()
                        .forEach(category -> categories.merge(category, 1L, Long::sum));
                var fingerprints = jobs.stream().filter(job -> job.analysis() != null)
                        .map(job -> job.analysis().fingerprint())
                        .filter(value -> value != null && !value.isBlank()).collect(Collectors.toSet());
                if (fingerprints.stream().anyMatch(seen::contains)) {
                    recurring++;
                }
                seen.addAll(fingerprints);
            }
            start = page.nextKey();
        } while (!start.isEmpty());
        var rates = new ArrayList<DailyRate>();
        daily.forEach((date, counts) -> rates.add(new DailyRate(date, counts[0], counts[1],
                counts[0] == 0 ? null : 100.0 * counts[1] / counts[0])));
        return new WorkflowAnalytics(from, until, total, failed, recurring, analyzed, rates, categories);
    }
}
