# Architecture

This repository provides a developer dashboard, backend health API, and GitHub
Actions REST access and standalone deterministic failure analysis with established
component boundaries, DynamoDB analysis persistence, and S3 job log storage.
Failed-workflow webhooks drive asynchronous CI ingestion through SQS.
End-user authentication is not implemented.

## Backend

All application packages live beneath `com.githubci.intelligence` so Spring
Boot's default component scanning finds controllers, services, and configuration.

| Package | Intended responsibility |
| --- | --- |
| `controllers` | HTTP routing and request/response handling |
| `services` | Application use cases and business logic |
| `repositories` | DynamoDB Enhanced Client persistence and conditional processing transitions |
| `models` | Stored analysis records, processing states, and table mapping |
| `dto` | API request and response contracts |
| `configuration` | Spring beans and runtime configuration |
| `exceptions` | Application exceptions and centralized HTTP error handling |

`HealthController` delegates to `HealthService`, which produces a `HealthResponse`
using the configured application name and an injectable UTC `Clock`.
`GlobalExceptionHandler` maps MVC, validation, and unexpected failures to the
`ApiError` JSON contract; unexpected failures are logged without exposing details.
`WebConfiguration` provides the clock and an environment-configured CORS allowlist
for `/api/**`. Local and production profiles configure CORS and Actuator exposure.
`DynamoDbConfiguration` provides AWS SDK v2 clients using environment-selected
endpoint/region and the default credentials chain. No tables are created by
application startup.

`GitHubController` exposes paginated workflow runs and jobs beneath
`/api/github/repositories/{owner}/{repo}`. `GitHubService` calls GitHub's official
REST endpoints using an environment-provided token, with DTOs for repositories,
workflow definitions, runs, jobs, and steps. Pagination is caller-driven with
`page`, `per_page`, and GitHub's `total_count` envelope. The service also supports
individual workflow/run details and failed-job logs without exposing additional
HTTP routes. Workflow analysis persists run metadata, failed jobs and diagnoses.
`WorkflowRunAnalysisService` fetches completed run metadata, claims a
repository/run/attempt using `WorkflowAnalysisRepository`, analyzes that specific
attempt's jobs, and conditionally commits the result. Completed results are
reused; failed or lease-expired work is retryable. Token-fenced conditional
writes prevent stale workers from overwriting newer results. A sparse GSI lists
completed failures by workflow creation time. See [DynamoDB access patterns](dynamodb.md).
`GitHubWebhookService` authenticates and validates failed-workflow events and
publishes `WorkflowEvent` messages before acknowledging HTTP requests.
`SqsWorkflowEventConsumer` polls SQS and calls the transport-independent
`WorkflowEventWorker`. A separate ID/run/attempt ledger in DynamoDB fences
concurrent deliveries and tracks `PROCESSING`/`FAILED`/`COMPLETED` states.
Heartbeat renewal, retry backoff and native SQS DLQ redrive support recovery.
Messages are deleted only after all required work and durable completion.
The existing synchronous analysis route remains available.

`GitHubConfiguration` provides timeout-bounded `RestClient` instances with
automatic redirects disabled. Authenticated API calls are restricted to
GitHub.com; log downloads use an unauthenticated client and validate each HTTPS
redirect against allowed GitHub Actions/storage hosts. Downloads have size and
redirect limits. `GitHubException` carries sanitized errors and rate-limit retry
metadata into `GlobalExceptionHandler`; upstream bodies, signed URLs, and
transport exceptions are not logged. Missing tokens disable only GitHub access,
not application startup or health. See the [API documentation](../README.md#github-actions-api)
for error statuses, limits, and deployment access-control requirements.

`FailureAnalyzerService` analyzes supplied plain-text logs using precompiled
regular expressions and bounded local context. It has no dependencies on GitHub,
AWS, network clients or an AI provider, and can be constructed without Spring.
Its immutable `FailureAnalysis` DTO separates observed diagnostics, line-numbered
evidence and surrounding context from suspected causes and investigation advice.
Specific diagnostics outrank build wrappers; equally strong conflicting categories
remain unknown. PostgreSQL attribution requires connection-specific supporting
evidence, not merely a generic network error. Analysis is wired to both the
synchronous HTTP analysis route and asynchronous webhook worker, but not the frontend.
See [local failure analysis](../README.md#local-failure-analysis)
for the categories, confidence semantics and local test command.

`LogStorageService` stores sanitized UTF-8 gzip logs through AWS SDK for Java v2.
Keys include repository ID, run ID, attempt and job ID; DynamoDB stores the key
with bounded analysis excerpts rather than full logs. `LogRedactionService`
runs before analysis and storage to remove common credentials and authentication
headers. Storage failures prevent completion and preserve the existing retry
semantics. Retrieval and deletion are backend service operations, not public
HTTP endpoints. Local development uses LocalStack, and storage unit tests mock
the SDK client. See [S3 configuration and retention](../README.md#s3-job-log-storage).

Tests cover the health contract with a fixed clock, JSON exception responses,
Jakarta Bean Validation, full-context endpoint wiring, CORS, and production
configuration without external services. Mocked GitHub HTTP responses cover
pagination, DTO mapping, authentication, permissions, rate limiting, log download
safety, and transport failures without network access or real credentials.
Standalone analyzer tests cover each category, evidence extraction, wrapper
precedence, ambiguous diagnostics and network-error false-positive protection.
Repository integration tests use Testcontainers with real DynamoDB Local to
verify serialization, queries, conditional claims, retries and stale-worker fencing.
S3 integration tests use Testcontainers with LocalStack for upload, retrieval,
overwrite and deletion without real AWS resources.

## Frontend

| Folder | Intended responsibility |
| --- | --- |
| `components` | Reusable presentation components |
| `pages` | Overview, Repositories, Workflow Failures, Analytics, and Settings |
| `services` | Centralized Axios API client and explicitly labeled demo fixtures |
| `hooks` | Reusable React hooks |
| `types` | Shared TypeScript contracts |
| `layouts` | Shared application shell |

The application uses Material UI's theme and CSS baseline with a responsive
Drawer/AppBar shell. React Router provides nested routes and a not-found page;
production hosts must fall back to `index.html` for client routes (the provided
nginx configuration does this).

TanStack Query owns server state and shares the health query between all
indicators. The centralized Axios client uses public, build-time
`VITE_API_BASE_URL` configuration (default `http://localhost:8080` locally,
`/` in Docker for same-origin API requests) and an
8-second timeout. Health responses are validated before rendering; transport
and payload failures are surfaced, not replaced with demo success.
The health query polls every 30 seconds and supports manual retries.
Other pages use local demo fixtures; search, repository filters, and period
selectors do not make API requests. No component test framework is configured.

## Infrastructure and operations

[The CDK app](../infrastructure/bin/github-ci-intelligence.ts) creates an
environment-agnostic stack with a private, encrypted, TLS-only S3 log bucket
and encrypted, TLS-only SQS workflow queue and dead-letter queue.
Its lifecycle expires logs after 30 days and aborts incomplete uploads after one
day; versioning is disabled. The bucket is retained on stack removal. Deployment
must separately provide the backend role and DynamoDB table.
Compiled JavaScript and synthesized templates are generated artifacts, not source files.

[`docker`](../docker) holds multistage frontend and backend Dockerfiles, dedicated
build-context ignore files, and Nginx configuration.
[`docker-compose.yml`](../docker-compose.yml) connects both services on a bridge
network, initializes a persistent DynamoDB Local table before starting the
backend, and waits for backend health before starting Nginx. Nginx serves the SPA
and proxies `/api` requests unchanged to `backend:8080`; the browser never uses
Docker-internal hostnames. Both services have container health checks.
[`CI`](../.github/workflows/ci.yml) validates all three
components without AWS credentials or deployment permissions.

See the [root README](../README.md) for setup and execution commands.
