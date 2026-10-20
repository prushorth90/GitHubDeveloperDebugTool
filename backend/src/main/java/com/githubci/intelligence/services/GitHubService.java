package com.githubci.intelligence.services;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubJobs;
import com.githubci.intelligence.dto.GitHubWorkflow;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.GitHubWorkflowRuns;
import com.githubci.intelligence.exceptions.GitHubException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class GitHubService {

    private static final int MAX_LOG_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private final RestClient api;
    private final RestClient downloads;
    private final String token;

    public GitHubService(
            @Qualifier("githubApiClient") RestClient api,
            @Qualifier("githubLogClient") RestClient downloads,
            @Value("${github.token:}") String token) {
        this.api = api;
        this.downloads = downloads;
        this.token = token;
    }

    public GitHubWorkflowRuns listWorkflowRuns(String owner, String repo, int page, int perPage) {
        validatePagination(page, perPage);
        GitHubWorkflowRuns result = get(repositoryPath(owner, repo)
                + "/actions/runs?page=" + page + "&per_page=" + perPage, GitHubWorkflowRuns.class);
        if (result.workflowRuns() == null || result.totalCount() < 0) {
            throw invalidResponse();
        }
        return result;
    }

    public GitHubWorkflow getWorkflowDetails(String owner, String repo, long workflowId) {
        validateId(workflowId);
        return get(repositoryPath(owner, repo) + "/actions/workflows/" + workflowId, GitHubWorkflow.class);
    }

    public GitHubWorkflowRun getWorkflowRun(String owner, String repo, long runId) {
        validateId(runId);
        return get(repositoryPath(owner, repo) + "/actions/runs/" + runId, GitHubWorkflowRun.class);
    }

    public GitHubJobs listJobs(String owner, String repo, long runId, int page, int perPage) {
        validateId(runId);
        validatePagination(page, perPage);
        GitHubJobs result = get(repositoryPath(owner, repo) + "/actions/runs/" + runId
                + "/jobs?page=" + page + "&per_page=" + perPage, GitHubJobs.class);
        if (result.jobs() == null || result.totalCount() < 0) {
            throw invalidResponse();
        }
        return result;
    }

    public String getFailedJobLogs(String owner, String repo, long jobId) {
        validateId(jobId);
        String path = repositoryPath(owner, repo) + "/actions/jobs/" + jobId;
        GitHubJob job = get(path, GitHubJob.class);
        if (!"failure".equals(job.conclusion()) && !"timed_out".equals(job.conclusion())) {
            throw new GitHubException(HttpStatus.CONFLICT, "Logs are only retrieved for failed or timed-out jobs");
        }
        try {
            LogResponse response = api.get().uri(path + "/logs")
                    .header(HttpHeaders.AUTHORIZATION, authorization())
                    .exchange((request, reply) -> readLogResponse(reply, false));
            for (int redirect = 0; response.location() != null; redirect++) {
                if (redirect >= MAX_REDIRECTS) {
                    throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub log redirect limit exceeded");
                }
                URI location = safeLogLocation(response.location());
                // Download URLs are signed: never forward the API token, even on a later redirect.
                response = downloads.get().uri(location)
                        .exchange((request, reply) -> readLogResponse(reply, true));
            }
            return response.content();
        } catch (RestClientException exception) {
            throw networkFailure();
        }
    }

    private <T> T get(String path, Class<T> type) {
        try {
            return api.get().uri(path).header(HttpHeaders.AUTHORIZATION, authorization())
                    .exchange((request, response) -> {
                        checkStatus(response, false);
                        T body = response.bodyTo(type);
                        if (body == null) {
                            throw invalidResponse();
                        }
                        return body;
                    });
        } catch (RestClientException exception) {
            // Transport/conversion exceptions can contain request headers or upstream bodies.
            throw networkFailure();
        }
    }

    private LogResponse readLogResponse(ClientHttpResponse response, boolean download) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (location == null) {
                throw invalidResponse();
            }
            return new LogResponse(location, null);
        }
        if (download && (status == 401 || status == 403)) {
            throw new GitHubException(HttpStatus.GONE, "GitHub job log download has expired or is unavailable");
        }
        checkStatus(response, true);
        byte[] bytes = response.getBody().readNBytes(MAX_LOG_BYTES + 1);
        if (bytes.length > MAX_LOG_BYTES) {
            throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub job logs exceed the 10 MiB download limit");
        }
        if (bytes.length == 0) {
            throw new GitHubException(HttpStatus.GONE, "GitHub job logs are unavailable or expired");
        }
        return new LogResponse(null, new String(bytes, StandardCharsets.UTF_8));
    }

    private void checkStatus(ClientHttpResponse response, boolean logs) throws IOException {
        int status = response.getStatusCode().value();
        HttpHeaders headers = response.getHeaders();
        if (status == 429 || (status == 403
                && ("0".equals(headers.getFirst("X-RateLimit-Remaining"))
                || headers.containsHeader(HttpHeaders.RETRY_AFTER)
                || new String(response.getBody().readNBytes(16 * 1024), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT).contains("rate limit")))) {
            throw new GitHubException(HttpStatus.TOO_MANY_REQUESTS,
                    "GitHub API rate limit exceeded; retry later", retryAfter(headers));
        }
        if (status == 401) {
            throw new GitHubException(HttpStatus.UNAUTHORIZED, "GitHub authentication failed; check the server token");
        }
        if (status == 403) {
            throw new GitHubException(HttpStatus.FORBIDDEN, "GitHub access denied; check token permissions");
        }
        if (logs && (status == 404 || status == 410)) {
            throw new GitHubException(HttpStatus.GONE, "GitHub job logs are unavailable or expired");
        }
        if (status == 404) {
            throw new GitHubException(HttpStatus.NOT_FOUND, "GitHub resource not found or not accessible");
        }
        if (status < 200 || status >= 300) {
            throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned an unexpected response");
        }
    }

    private String retryAfter(HttpHeaders headers) {
        String retry = headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            if (retry != null) {
                return Long.toString(Math.max(1, Long.parseLong(retry)));
            }
            String reset = headers.getFirst("X-RateLimit-Reset");
            if (reset != null) {
                return Long.toString(Math.max(1, Long.parseLong(reset) - Instant.now().getEpochSecond()));
            }
        } catch (NumberFormatException exception) {
            // GitHub may omit usable retry metadata; secondary limits require at least one minute.
        }
        return "60";
    }

    private URI safeLogLocation(String value) {
        URI location;
        try {
            location = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw invalidResponse();
        }
        String host = location.getHost();
        if (!"https".equalsIgnoreCase(location.getScheme()) || host == null
                || location.getRawUserInfo() != null || location.getRawFragment() != null
                || (location.getPort() != -1 && location.getPort() != 443)
                || !(host.equals("api.github.com") || host.endsWith(".actions.githubusercontent.com")
                || host.endsWith(".blob.core.windows.net"))) {
            throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned an unsafe log redirect");
        }
        return location;
    }

    private String authorization() {
        if (token == null || token.isBlank()) {
            throw new GitHubException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub integration requires GITHUB_TOKEN");
        }
        if (token.chars().anyMatch(character -> character <= 32 || character >= 127)) {
            throw new GitHubException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_TOKEN contains invalid characters");
        }
        return "Bearer " + token;
    }

    private String repositoryPath(String owner, String repo) {
        if (owner == null || !owner.matches("[A-Za-z0-9-]{1,39}")
                || repo == null || !repo.matches("[A-Za-z0-9_.-]{1,100}")
                || ".".equals(repo) || "..".equals(repo)) {
            throw new GitHubException(HttpStatus.BAD_REQUEST, "Invalid GitHub repository owner or name");
        }
        return "/repos/" + owner + "/" + repo;
    }

    private void validateId(long id) {
        if (id < 1) {
            throw new GitHubException(HttpStatus.BAD_REQUEST, "GitHub identifiers must be positive");
        }
    }

    private void validatePagination(int page, int perPage) {
        if (page < 1 || perPage < 1 || perPage > 100) {
            throw new GitHubException(HttpStatus.BAD_REQUEST, "page must be positive and per_page must be between 1 and 100");
        }
    }

    private GitHubException invalidResponse() {
        return new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned an invalid response");
    }

    private GitHubException networkFailure() {
        return new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub request failed or returned unreadable data; retry later");
    }

    private record LogResponse(String location, String content) {
    }
}
