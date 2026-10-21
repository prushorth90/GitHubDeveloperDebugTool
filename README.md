# GitHub CI Intelligence

A full-stack developer tool with a GitHub Actions REST integration and developer
dashboard and a deterministic CI failure analyzer. Workflow analyses are persisted
in Amazon DynamoDB; sanitized, compressed job logs are stored in Amazon S3.
Authenticated failed-workflow webhooks are queued in Amazon SQS for asynchronous processing.
End-user authentication is not implemented.

The [persisted analysis REST API](docs/analysis-api.md) provides repository
discovery, paginated run/failure history, failure details, statistics and
fingerprint-based similar failures. **Keep this unauthenticated API local or
behind an access-controlled boundary until authorization is implemented.**
Existing tables require the [read-index migration and backfill](docs/dynamodb.md#read-api-schema-and-migration)
before using these routes.

## Technology

- **Backend:** Java 21, Spring Boot 4.1.1, Maven.
- **Persistence:** AWS SDK for Java v2, DynamoDB Enhanced Client, S3, DynamoDB Local and LocalStack.
- **Frontend:** React 19, TypeScript 5.9, Vite 8, Material UI 9 with Emotion,
  React Router, TanStack Query, and Axios.
- **Infrastructure:** AWS CDK v2 in TypeScript, with a private, encrypted S3 log bucket.
- **Tooling:** npm workspaces, Docker Compose, GitHub Actions.

Direct npm dependencies are pinned and the root `package-lock.json` locks the
dependency tree. Spring Boot's parent POM manages backend dependency versions.
The CDK CLI and library have independent version numbers.

## Structure

```text
.
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/githubci/intelligence/
│       │   │   ├── GitHubCiIntelligenceApplication.java
│       │   │   ├── controllers/
│       │   │   ├── services/
│       │   │   ├── repositories/
│       │   │   ├── models/
│       │   │   ├── dto/
│       │   │   ├── configuration/
│       │   │   └── exceptions/
│       │   └── resources/application.yaml
│       └── test/java/com/githubci/intelligence/
├── frontend/
│   ├── public/
│   ├── src/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── services/
│   │   ├── hooks/
│   │   ├── types/
│   │   ├── layouts/
│   │   ├── App.tsx
│   │   └── main.tsx
│   ├── index.html
│   ├── package.json
│   ├── tsconfig*.json
│   └── vite.config.ts
├── infrastructure/
│   ├── bin/                    # CDK application entry point
│   ├── lib/                    # Stack definitions
│   ├── cdk.json
│   ├── package.json
│   └── tsconfig.json
├── docker/                     # Dockerfiles, build-context ignores, nginx
├── docker-compose.yml
├── .env.example                # Optional Compose configuration
├── .github/workflows/ci.yml
├── docs/
├── .editorconfig
├── .gitignore
├── .dockerignore
├── .nvmrc
├── package.json                # npm workspace commands
└── package-lock.json
```

Unused source folders contain `.gitkeep` files so Git preserves them. See
[architecture and folder responsibilities](docs/architecture.md).

## Prerequisites

- JDK **21** and Maven **3.9+**. Check `java -version` and `mvn -version`;
  Maven must use the intended JDK via `JAVA_HOME`.
- Node.js **22.12+ on the 22 LTS line**, or a supported newer LTS (24+),
  and npm **10+**. `.nvmrc` selects Node 22.
- Docker with Compose v2, for DynamoDB Local and repository integration tests.
- AWS credentials and an AWS account are **not** needed to synthesize the
  stack or use local emulators. Deployment requires an AWS account.

All commands below run from the repository root unless stated otherwise.

```sh
# Optional, if using nvm
nvm use

# Install both JavaScript workspaces; do not maintain separate lockfiles.
npm ci
```

## Run the backend

Start and initialize the local database, then export the backend environment
(Maven does not automatically load `.env`):

```sh
docker compose up -d dynamodb-local localstack
docker compose run --rm dynamodb-init
docker compose run --rm s3-init
docker compose run --rm sqs-init
export DYNAMODB_ENDPOINT=http://localhost:8000
export AWS_REGION=us-east-1 AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local
# Enable consumption in this process (Compose enables it automatically).
export SQS_WORKER_ENABLED=true
```

```sh
mvn -f backend/pom.xml spring-boot:run
```

The server listens on `http://localhost:8080`; set `PORT` to change it.
Stop with Ctrl+C. Check the API with:

```sh
curl http://localhost:8080/api/health
```

```json
{"status":"UP","service":"github-ci-intelligence","timestamp":"2026-10-17T03:00:00Z"}
```

The timestamp is the current UTC time. This endpoint reports application
liveness, not the state of external services. Actuator also exposes
`/actuator/health` without internal details; `/actuator/info` is available
locally. Requesting `/` returns HTTP 404.

API errors use a consistent JSON contract, for example:

```json
{"timestamp":"2026-10-17T03:00:00Z","status":404,"error":"Not Found","message":"Not Found","path":"/api/missing"}
```

Validation failures return 400. Unexpected failures return 500 with a generic
message; details are logged on the server rather than exposed to clients.

### Environment configuration

- **Local (default):** `application-local.yaml` allows API access from
  `http://localhost:5173` and `http://127.0.0.1:5173` (Vite), plus the same
  hosts on port 3000 (Docker). CORS applies only to `/api/**`, without cookies
  or credentialed requests.
- **Production:** set `SPRING_PROFILES_ACTIVE=prod`. Only Actuator health is
  exposed; graceful shutdown is enabled. No cross-origin access is allowed
  unless `CORS_ALLOWED_ORIGINS` supplies a comma-separated list of exact
  frontend origins. CORS is not authentication.
- Both environments accept `PORT` (default `8080`) and
  `CORS_ALLOWED_ORIGINS` to override the origin list.

```sh
SPRING_PROFILES_ACTIVE=prod \
  CORS_ALLOWED_ORIGINS=https://app.example.com \
  mvn -f backend/pom.xml spring-boot:run
```

```sh
# Compile, run unit/configuration tests, and package
mvn -f backend/pom.xml verify

# Run the packaged application
java -jar backend/target/github-ci-intelligence-backend.jar
```

DynamoDB configuration, table schema, access patterns, processing leases,
production setup, and integration test commands are documented in
[DynamoDB persistence](docs/dynamodb.md). No JPA, Lombok, or authentication library
is included.

### S3 job log storage

`LogStorageService.upload(repositoryId, runId, attempt, jobId, logs)` redacts
logs, encodes UTF-8, compresses with gzip, and returns a deterministic key:

```text
logs/repositories/<repository-id>/runs/<run-id>/attempts/<attempt>/jobs/<job-id>.log.gz
```

Only failed/timed-out jobs with nonempty available logs are uploaded. Redaction
also runs **before analysis**, so persisted evidence/signatures do not retain
recognized credentials. DynamoDB contains `logObjectKey` references and bounded
analysis excerpts, never full log contents. Each excerpt line is capped at 2,000
characters (with an explicit truncation marker); the full sanitized log stays in
S3. Existing records without a key remain readable; there is no automatic
backfill. Re-uploading the same job/attempt overwrites the same object.

`retrieve(key)` downloads and decompresses UTF-8 logs, applying redaction again.
`delete(key)` is idempotent for missing objects. Both accept only managed log
keys, not arbitrary bucket paths. These are backend service methods, not new
public download/delete endpoints. Expired/missing objects produce an explicit
410 storage exception; storage/permission/network failures produce 503, invalid
gzip produces 502. Upload failures prevent analysis completion and leave it
retryable. A database failure after upload can leave an unreferenced object;
retry overwrites it, and lifecycle expiration eventually removes it. Completed
cached analyses can still reference logs that have expired.

| Variable | Default | Purpose |
| --- | --- | --- |
| `S3_LOG_BUCKET` | `github-ci-logs` | Pre-provisioned bucket |
| `S3_ENDPOINT` | local: `http://localhost:4566`; prod: unset | Emulator endpoint; omit for AWS |
| `S3_PATH_STYLE_ACCESS` | local: `true`; prod: `false` | Path-style addressing for emulators |
| `AWS_REGION` | `us-east-1` | S3 and DynamoDB region |

The SDK uses the standard credential provider chain, shared with DynamoDB;
there are no embedded real credentials or Java dummy-credential fallbacks.
The local commands above initialize LocalStack using dummy environment
credentials, with no real AWS resources. Compose supplies its internal S3
endpoint and dummy credentials even under the `prod` Spring profile. LocalStack
data uses a named volume; it is development storage, not a backup guarantee.
Unit tests mock `S3Client` and require neither Docker nor AWS.

For deployment, the CDK stack provisions an SSL-only, public-access-blocked,
SSE-S3 encrypted bucket. Its enabled lifecycle rule expires `logs/` objects
**30 days after creation**, and aborts incomplete multipart uploads after one
day. Versioning is disabled so expiration does not leave retained noncurrent
log versions. AWS lifecycle deletion is asynchronous, not an exact 30-day
deadline; re-upload resets object age. The bucket is retained when the stack
is removed. Configure the backend with `SPRING_PROFILES_ACTIVE=prod`,
`S3_LOG_BUCKET=<LogBucketName output>`, and no endpoint override. Grant its
workload role only `s3:PutObject`, `s3:GetObject`, and `s3:DeleteObject` on
`arn:aws:s3:::<bucket>/logs/*`; no bucket-creation permission is needed. Startup
never creates buckets or changes lifecycle rules. The stack does not yet deploy
the backend or provision its IAM role/DynamoDB table.

Redaction covers authentication/cookie/API-key headers, common secret
assignments, URL credentials/signed queries, GitHub/AWS/Slack/JWT tokens,
private keys, and the configured GitHub token/webhook secret. It is
best-effort, not proof that arbitrary logs are secret-free: custom encodings,
unknown formats or unlabelled values may evade it. Keep GitHub's secret
masking enabled, never print secrets, and protect both storage and the API
behind an access-control boundary. No raw logs, signed URLs, authentication
headers or SDK exception messages are logged by storage operations.

```sh
# S3 unit tests (no Docker/AWS)
mvn -f backend/pom.xml -Dtest=LogStorageServiceTest,LogRedactionServiceTest,WorkflowRunAnalysisIntegrationTest test
# LocalStack round-trip integration test (Docker required)
mvn -f backend/pom.xml -DskipTests=false -Dit.test=LogStorageServiceIT verify
```

### GitHub workflow webhooks

`POST /api/webhooks/github` receives GitHub `workflow_run` webhooks. Set
`GITHUB_WEBHOOK_SECRET` in the **backend process environment** to the same
high-entropy secret configured on the GitHub webhook. This is independent of
`GITHUB_TOKEN`; no GitHub API token is needed to receive webhooks. The backend
does not automatically load `.env` files for Maven runs. Docker Compose passes
`GITHUB_WEBHOOK_SECRET` from its environment or the root `.env` file.

The endpoint authenticates `X-Hub-Signature-256` with HMAC-SHA256 over the
**original request bytes**, using a constant-time digest comparison, before
parsing JSON or checking event type or duplicate delivery IDs. Never reformat
or reserialize a payload before verifying it.

- Only `X-GitHub-Event: workflow_run`, `action: completed`, and
  `workflow_run.status: completed` are considered. Failed conclusions
  `failure`, `timed_out`, and `startup_failure` are published; other valid
  conclusions (including success/cancelled) are ignored.
- Enqueued, duplicate, and unrelated events return **204** with no body.
  A relevant event receives 204 only after SQS accepts the message.
  Unrelated event types are authenticated but do not need to be parsed.
- Missing/invalid signatures return **401**; malformed signed workflow JSON,
  required metadata, or delivery headers return **400**. Missing server secret
  returns **503**, as does failed/unconfirmed queue publication, without disabling health or other API routes. Errors use
  the existing `ApiError` contract and never include request bodies or secrets.
- `WorkflowEvent` is a queue-ready DTO with `repository_id`, `repository_name`
  (GitHub's `repository.full_name`, e.g. `octo/demo`), nullable `installation_id`,
  `workflow_run_id`, `run_attempt`, `conclusion`, and `delivery_id`.
  Positive numeric IDs and attempts are required. Installation may be absent.
- Only extracted `WorkflowEvent` metadata is sent to SQS, not the webhook body
  or signature. The request never retrieves job logs or analyzes work.
- Every relevant delivery is published, including redeliveries: there is no
  process-local deduplication that could lose work after a failed send.
  SQS can accept a message even if the send response is lost; retries are safe.
  The worker uses durable repository-ID/run-ID/attempt idempotency, not delivery IDs.
  Bodies, signatures, tokens, branch names and commit messages are not logged.

#### Asynchronous worker, retries and recovery

`WorkflowEventWorker.process(event)` is a plain Java entry point, independent of
SQS receipt handles and Lambda event classes. It can run directly in tests or a
local harness. `SqsWorkflowEventConsumer` is the polling adapter, enabled with
`SQS_WORKER_ENABLED=true`. Disabled by default for host-run processes/tests,
it is enabled in Compose. Separate web/worker processes can share the same queue,
table and bucket; the web process can leave polling disabled.

The worker conditionally claims a durable event ledger item in the existing
DynamoDB table with `pk=REPOSITORY#<id>#RUN#<id>#ATTEMPT#<n>`, `sk=EVENT`.
States are `PROCESSING`, retryable `FAILED`, and `COMPLETED`; SQS itself represents
queued work. Ledger items retain processing-attempt counts, timestamps, a random
ownership token and a lease deadline. They deliberately have no automatic TTL:
deleting them removes durable duplicate protection. Result items retain the
existing name-based API/query schema.

The worker fetches the **specific run attempt**, validates repository ID/run ID/
attempt/conclusion against the event, retrieves failed/timed-out jobs, redacts
logs before analysis, uploads gzip logs under deterministic S3 keys, and commits
results through the existing analyzer. Missing/expired/empty GitHub logs remain
explicit `UNAVAILABLE` per-job results, not fabricated diagnoses.

An event is marked `COMPLETED` only after required uploads and DynamoDB result
persistence finish. Only then may the adapter delete the SQS message. Completed
duplicates skip GitHub/S3 entirely. A lost delete response is safe to replay.
If result persistence succeeds but event completion fails, retry reuses the
completed result. If an upload succeeds before a later failure, retry can
re-upload the same key rather than create duplicate objects; no cross-service
transaction or exactly-once execution is claimed.

Conditional token/deadline checks fence stale workers. A five-second heartbeat
renews the event lease and SQS visibility while processing. Heartbeat failure
prevents successful acknowledgement. Analysis leases are renewed between pages/
failed jobs and before completion. `DYNAMODB_PROCESSING_LEASE` defaults to `PT5M`
and must be at least 30 seconds and exceed the longest individual job's bounded
download/upload/analyze operation. A crash leaves a claim recoverable after
lease expiry. Workers need synchronized clocks.

Failures remain on SQS with exponential visibility backoff (30 seconds through
15 minutes). The provisioned redrive policy moves messages to the DLQ after five
receives, including invalid/poison messages. Retry-delay failures are logged and
the existing visibility timeout still applies. Monitor queue age, worker errors,
and DLQ depth; after fixing the cause, use SQS redrive to replay DLQ messages.
Do not delete an unresolved message or a completed idempotency record to retry it.
A future Lambda adapter should report per-message batch failures and call the
same worker, never acknowledge a whole batch after a partial failure.

| Variable | Purpose |
| --- | --- |
| `SQS_ENDPOINT` | SDK override; local profile defaults to `http://localhost:4566`, unset in AWS |
| `SQS_QUEUE_URL` | Required in production; local defaults to `http://localhost:4566/queue/us-east-1/000000000000/workflow-events` |
| `SQS_WORKER_ENABLED` | `false` by default; Compose sets `true` |
| `SQS_VISIBILITY_SECONDS` | Initial/renewed visibility, default 120, supported range 30-43200 seconds |

Local setup uses `docker compose run --rm sqs-init` to provision the standard
queue and a 14-day DLQ in LocalStack. Queue retention is four days. The CDK stack
provisions equivalent encrypted, TLS-only SQS resources and outputs their URLs/
ARN. Production must supply workload roles and the DynamoDB table separately:
web publishers need `sqs:SendMessage`; workers need `sqs:ReceiveMessage`,
`sqs:DeleteMessage`, `sqs:ChangeMessageVisibility`, DynamoDB `GetItem`/`PutItem`/
`UpdateItem`, and S3 read/write permissions for the managed log prefix.
Keep queue access restricted to trusted publishers.

Local tests need no AWS account or Lambda runtime:

```sh
# Mocked queue adapters, worker and authenticated webhook contract:
mvn -f backend/pom.xml -Dtest=SqsWorkflowEventTest,WorkflowEventWorkerTest,GitHubWebhookControllerTest test
# Real LocalStack SQS/S3/DynamoDB, partial failures, fencing and DLQ redrive:
mvn -f backend/pom.xml -Dtest=WorkflowQueueIT test
```

#### Test locally through an HTTPS tunnel

1. Generate a high-entropy secret in a password manager and configure it through
   your IDE's private environment or a secure terminal prompt. For example,
   in macOS's default zsh, the following reads silently rather than putting
   the value in shell history:

   ```sh
   read -rs 'GITHUB_WEBHOOK_SECRET?Webhook secret: '; echo
   export GITHUB_WEBHOOK_SECRET
   mvn -f backend/pom.xml spring-boot:run
   ```

   Do not use a literal secret in a command, commit it, paste it into chat,
   put it in frontend/Vite configuration, or enable shell tracing (`set -x`).
   Logs contain repository identifiers; keep local logs private as well.

2. In another terminal, start an installed tunnel, for example:

   ```sh
   ngrok http 8080 --inspect=false
   ```

   Use its assigned **HTTPS** URL. Disable request inspection/retention and
   avoid public request-bin or shared debugging services: the tunnel provider
   can see webhook payloads. Prefer a disposable, non-sensitive test repository.
   A tunnel to this port also exposes other backend routes; use a private test
   environment, never a backend with privileged production credentials.

3. In the test repository's **Settings > Webhooks > Add webhook**, set:
   - Payload URL: `https://YOUR-TUNNEL-HOST/api/webhooks/github`
   - Content type: `application/json`
   - Secret: enter the same secret directly in GitHub's secret field
   - SSL verification: enabled
   - Events: select individual events and choose **Workflow runs**

   A GitHub App webhook may also include `installation.id`; a repository
   webhook can omit it. The initial signed `ping` should receive 204 without
   creating a workflow metadata log entry.

4. Run a small **failing** Actions workflow and wait for completion. In GitHub's **Recent
   deliveries**, verify the completed `workflow_run` delivery received 204.
   The backend should log a queue acceptance line with its delivery ID, run ID,
   attempt, and conclusion. With polling enabled, verify the stored analysis and
   sanitized S3 objects. Use **Redeliver** for the same delivery: it should
   still receive 204 and log queue acceptance, but the completed worker skips work.
   Non-completed workflow events should also receive 204 with no metadata log.
   To check rejection, temporarily configure a different secret in the test
   webhook, trigger an event, and verify 401; restore the matching secret.

5. Stop the tunnel when finished, remove/disable the test webhook, and clear the
   secret from the terminal with `unset GITHUB_WEBHOOK_SECRET`. Do not share
   delivery payloads, request signatures, tunnel inspection captures, or secrets.

Automated coverage (no tunnel or live GitHub credentials required):

```sh
mvn -f backend/pom.xml -Dtest=GitHubWebhookControllerTest test
```

### GitHub Actions API

Set `GITHUB_TOKEN` in the backend process environment (or supply it through your
deployment's secret manager). Use a fine-grained personal access token or GitHub
App installation token with **Actions: read** access to the selected repositories.
A classic token needs `repo` scope for private repositories. Never put this token
in frontend configuration, source code, shell history, or logs. The backend does
not automatically load `backend/.env`; export variables through your local
environment or IDE's secret configuration.

Without a token, health and other routes remain available; GitHub routes return
503 with a configuration error. A configured token is sent only to
`https://api.github.com`, using Spring `RestClient` and REST API version
`2022-11-28`. The API origin is deliberately restricted to GitHub.com.

| Endpoint | Response |
| --- | --- |
| `GET /api/github/repositories/{owner}/{repo}/workflows` | Workflow **runs**, not workflow definitions: `{"total_count":123,"workflow_runs":[...]}` |
| `GET /api/github/repositories/{owner}/{repo}/runs/{runId}/jobs` | Jobs with nested steps: `{"total_count":5,"jobs":[...]}` |
| `POST /api/github/repositories/{owner}/{repo}/runs/{runId}/analyze` | Synchronous failure analysis with repository, run ID and failed job results (see below) |

Both GET endpoints accept `page` (default `1`, positive) and `per_page` (default
`30`, range `1..100`). Each request fetches exactly one GitHub page, preserving
`total_count`; request successive pages until all results are retrieved or the
returned list is empty. Results are not cached or persisted and can change
between pages. DTOs preserve GitHub's snake_case field names, UTC timestamps,
and nullable conclusions/timestamps.

```sh
curl 'http://localhost:8080/api/github/repositories/OWNER/REPO/workflows?page=1&per_page=100'
curl 'http://localhost:8080/api/github/repositories/OWNER/REPO/runs/123/jobs?page=1&per_page=100'
```

`GitHubService` additionally retrieves workflow definitions by workflow ID,
individual run details, and plain-text logs by failed job ID. Log retrieval
first checks for `failure` or `timed_out`; other conclusions return 409.
The analysis endpoint uses failed-job logs internally; workflow details, run
details and raw logs do not have separate public HTTP endpoints. Log downloads
follow at most five redirects, validate **every** location, and use a separate
unauthenticated client so no API token reaches storage. Only HTTPS on port
443 (or the default port) is accepted, with no userinfo or fragments, for
`api.github.com`, subdomains of `actions.githubusercontent.com`, or subdomains
of `blob.core.windows.net`. Other destinations are rejected. Downloads are
limited to 10 MiB; connection/read timeouts are 5/20 seconds.

Errors use the existing `ApiError` contract:

- 400: invalid owner/repository, ID, or pagination.
- 401/403: GitHub authentication or permission failure.
- 404: resource missing or inaccessible (GitHub may hide private repositories).
- 429: primary/secondary rate limiting; `Retry-After` is supplied from GitHub's
  retry/reset metadata, or defaults to 60 seconds if unavailable/invalid.
- 410: expired, deleted, empty, or unavailable logs.
- 502: network/timeouts, malformed upstream data, unexpected status, unsafe
  redirects, or oversized logs.
- 503: missing or invalid server token configuration.

Requests are not retried automatically and do not sleep on rate limits.
Clients should respect `Retry-After` and use backoff for transient failures.
Upstream bodies, token values, signed log URLs, and transport exception details
are not written to application error logs or returned in API errors. Avoid
enabling HTTP wire/header tracing in production. Job log content itself can be
sensitive; callers must not log it.

**Access control:** these endpoints use the server's token and do not authenticate
callers. Keep the backend private or protect it with an authenticated gateway
before exposing it to other users. CORS is not authorization. The frontend's
sample-data pages are not yet connected to these endpoints.

Mocked HTTP tests cover pagination, DTO mapping, endpoint wiring, error responses,
rate limits, unavailable logs, bounded downloads, and safe redirects without
real tokens or calls to GitHub.

### Local failure analysis

Analyze a workflow run with an empty-body POST:

```sh
curl -X POST http://localhost:8080/api/github/repositories/OWNER/REPO/runs/123/analyze
```

The request synchronously retrieves all job pages (100 per page), selects jobs
with `failure` or `timed_out` conclusions, downloads their logs and runs the
existing analyzer and fingerprint service. It returns:

```json
{
  "repository": "OWNER/REPO",
  "runId": 123,
  "failedJobs": [
    {
      "job": {"id": 7, "run_id": 123, "name": "Build", "conclusion": "failure"},
      "logStatus": "AVAILABLE",
      "logMessage": null,
      "analysis": {
        "category": "JAVA_COMPILATION",
        "errorMessage": "[ERROR] /src/App.java:[12,4] cannot find symbol",
        "confidence": "HIGH",
        "suggestedInvestigation": "Inspect the compiler diagnostic and source location..."
      }
    }
  ]
}
```

The example abbreviates the job and analysis objects; each analysis includes all
fields documented below, including evidence, context, normalized signature and
the SHA-256 fingerprint. Each job retains its metadata and nested steps.
Runs without failed jobs return HTTP 200 with an empty `failedJobs` list.
Missing/expired logs (404/410), expired signed downloads and empty/blank logs
produce a per-job `logStatus: "UNAVAILABLE"`, an explanatory `logMessage` and
`analysis: null`; other jobs are still analyzed. No diagnosis or fingerprint is
invented for missing logs.

Invalid names/IDs and GitHub API errors use the error statuses above, including
`Retry-After` for rate limits; they do not return a partial success. An incomplete
jobs response also returns 502 rather than silently omitting jobs.
This manual analysis endpoint has no request body or automatic processing retries;
it remains synchronous, independently of the asynchronous webhook path.
It retrieves completed run metadata, claims the run attempt in DynamoDB, and
fetches that attempt's jobs before persisting the result. A repeated request
reuses a completed result; an active claim returns 409 with `Retry-After`.
Failed or expired processing can be retried safely. GitHub metadata is still
fetched on cache hits to enforce the existing upstream access checks.
Non-completed runs return 409. All work occurs in the HTTP request, so large
runs can take time. Results and selected sanitized log-derived evidence are
persisted in DynamoDB; full sanitized logs are gzip objects in S3, referenced
by `logObjectKey`. See [persistence limits and access patterns](docs/dynamodb.md).

`FailureAnalyzerService.analyze(String rawLogs)` accepts plain-text job logs and
returns an immutable `FailureAnalysis` record. It is a Spring service but can also
be constructed directly with `new FailureAnalyzerService()`; no Spring context,
GitHub token, AWS credentials, network requests or paid AI APIs are required.
It is used by the analysis endpoint but is not yet connected to the dashboard.

The result contains:

- `category`: `JAVA_COMPILATION`, `MAVEN_DEPENDENCY`, `JUNIT_TEST`,
  `POSTGRESQL_CONNECTION`, `DOCKER_BUILD`, `AUTHENTICATION`,
  `GITHUB_ACTIONS_CONFIGURATION`, or `UNKNOWN`.
- `errorMessage`: the observed diagnostic, not an invented explanation. Logs
  without a recognizable diagnostic receive an explicit absence message.
- `normalizedSignature` and `fingerprint`: normalized selected diagnostic evidence
  and its stable, lowercase, 64-character SHA-256 hash (see below).
- `suspectedCause` and `suggestedInvestigation`: hypotheses and next checks,
  **not** confirmed root causes.
- `confidence`: `HIGH` for specific diagnostics, `MEDIUM` for build/phase
  wrappers, or `LOW` for unknown/conflicting evidence. These are heuristic
  classification strengths, not statistical probabilities.
- `evidence`: matching lines, including corroborating context when required.
- `logContext`: up to two nonblank surrounding lines on either side of each
  selected diagnostic, within the same Actions log section. Both lists preserve
  one-based original line numbers; ANSI colors and Actions timestamps are removed.

Specific diagnostics take precedence over generic Maven/Docker wrappers. Distinct
categories with equally strong evidence produce `UNKNOWN` instead of guessing a
single causal chain. PostgreSQL classification requires connection-specific
PostgreSQL evidence on the diagnostic or within two lines in the same section;
an unrelated service-start message, port 5432 alone, or `ECONNREFUSED` alone does
not suffice. Explicit generic network targets are not associated with a different
nearby PostgreSQL target. Echoed Actions commands are not treated as diagnostics.
Null input is rejected with `IllegalArgumentException`; blank, successful and
unrecognized logs return low-confidence `UNKNOWN` results.

Run the standalone analyzer tests locally:

```sh
mvn -f backend/pom.xml -Dtest=FailureAnalyzerServiceTest,FailureFingerprintServiceTest test
```

Run the full-context endpoint integration tests with mocked GitHub HTTP responses:

```sh
mvn -f backend/pom.xml -Dtest=WorkflowRunAnalysisIntegrationTest test
```

Evidence and context can contain sensitive raw log content; treat the result
with the same access controls as the source logs and do not log it automatically.

#### Failure fingerprints

`FailureFingerprintService` hashes UTF-8 bytes of
`v1\n<CATEGORY_NAME>\n<normalizedSignature>`. The version prefix reserves a
normalization namespace; future rule changes must bump it rather than silently
mix incompatible fingerprints. It uses only extracted diagnostic evidence
(including corroborating evidence or all conflicting category diagnostics),
not the full raw log, line numbers, surrounding context or investigation advice.
Original diagnostics and evidence are unchanged.

Normalization removes ANSI colors and ISO-style date/time values (including
fractional seconds and timezone offsets), collapses whitespace, and substitutes
UUIDs and numeric/hexadecimal generated request, correlation, trace, span, run
and build IDs when explicitly labeled with `id`. Temporary paths under Unix
`/tmp`, `/private/tmp`, `/var/tmp`, macOS `/var/folders/.../T` or `C`, and standard
Windows temporary directories become `<tmp>/<filename>`; long numeric/hex file
suffixes are replaced, while meaningful filenames are retained. Java generated
lambda identifiers are replaced too. Loopback ports in the dynamic range
49152–65535 and explicitly labeled ephemeral ports are normalized. Other ports,
remote hosts, artifact coordinates/versions, exception names, source locations,
assertion values, status/exit codes and unlabeled numbers remain significant.
This deliberately favors missed matches over indiscriminate numeric replacement.
Unrecognized date formats, ID schemes and temporary roots are left unchanged.

Fingerprints are **candidate grouping keys, not proof of a shared root cause**.
Different problems can share a generic wrapper, test summary or compiler
diagnostic, especially when the missing symbol or underlying exception appears
only in surrounding context. Removing a timestamp, UUID, temporary directory
or dynamic port can also hide the cause when that value itself is faulty.
All logs without a recognizable diagnostic share the explicit absence signature
and `UNKNOWN` fingerprint; this is not a diagnosed failure group.

No grouping store or automatic remediation is implemented. Consumers should
compare category and normalized signature after hash lookup: equal hashes with
different signatures/categories indicate a hash collision and must remain
separate. SHA-256 collisions are extremely unlikely, but this comparison avoids
relying on that assumption alone. Over-normalization produces the **same**
signature, so hash comparison cannot detect it; inspect retained raw evidence,
context and confidence before merging incidents or acting automatically. Refine
and version the rules when a false match is discovered. Normalization is not
secret redaction; the signature may still contain sensitive values.

## Run the frontend

```sh
npm run dev
```

Open `http://localhost:5173` for the responsive developer dashboard. The sidebar
links to Overview, Repositories, Workflow Failures, Analytics, and Settings.
Repository search, failure filtering, and chart period selectors operate on
explicitly labeled sample data; no GitHub connection is required.

The backend health indicator calls `GET /api/health` using a shared Axios client
and TanStack Query. It shows checking, connected (`status: "UP"`), non-UP, invalid
response, and unreachable states. It refreshes every 30 seconds and offers a
manual retry. This is application liveness, not external dependency health.

The API origin defaults to `http://localhost:8080`. To override it, copy
`frontend/.env.example` to `frontend/.env.local` and set `VITE_API_BASE_URL`
(an origin without `/api`). Restart Vite after changes. Vite substitutes these
values at **build time**; production/Docker bundles need a rebuild with the
desired environment supplied to the build. Docker builds default to `/` instead,
so API requests use the frontend origin and Nginx's `/api` proxy.
Never put secrets in `VITE_*` values.
The backend's local CORS policy allows the standard Vite and Docker origins.
For another frontend port or production domain, configure the backend's
`CORS_ALLOWED_ORIGINS` accordingly. Local Vite development calls the backend
directly; Docker uses the same-origin proxy and does not require CORS.

```sh
npm run build --workspace frontend
npm run preview --workspace frontend
```

Preview serves the production bundle at `http://localhost:4173`.
It is a local preview server, not a production hosting solution.

## Run the infrastructure app

```sh
npm run build --workspace infrastructure
npm run infra:synth
```

Synthesis compiles TypeScript and writes CloudFormation artifacts under
`infrastructure/cdk.out/`. The stack defines the private S3 job log bucket,
its TLS-only bucket policy and 30-day expiration rule. Lookups are disabled
during synthesis so it can run without AWS access. A global CDK installation
is not required.

To review or deploy the bucket, the workspace also provides:

```sh
npm run diff --workspace infrastructure
npm run deploy --workspace infrastructure
```

Before deployment, configure your AWS account/region and credentials, review
the generated resources and costs, and bootstrap the target environment as
needed. These commands may access or modify AWS; CI never deploys.

## Docker

Install Docker Engine/Desktop with Compose v2 and start the Docker daemon.
No host Java, Maven, Node.js, AWS credentials, or application secrets are needed.
From the repository root:

```sh
# Optional: customize the documented defaults
cp .env.example .env

docker compose up --build
```

- Frontend: `http://localhost:3000` (production assets served by nginx).
- Backend: `http://localhost:8080` (Java 21 runtime).
- DynamoDB Local: `http://localhost:8000`, persisted in a named Docker volume.
- All published ports bind only to the local machine.

All services join the same Compose-managed bridge network. The browser calls
`/api/...` on `localhost:3000`; Nginx forwards the unchanged path to
`backend:8080` over that network. Docker-internal names never need to resolve in
the browser. React client routes fall back to `index.html`; API failures are
returned by the backend, not replaced by the SPA.

The backend uses a multistage Maven build and a non-root Java 21 JRE runtime.
Its health check probes `/actuator/health`. Compose waits for a healthy backend
before starting the frontend, and for successful DynamoDB table initialization
before starting the backend. The frontend uses a Node build stage and an Nginx
runtime whose health check probes `/healthz`. The UI's health indicator verifies
the full browser-to-proxy-to-backend path via `/api/health`.

### Container configuration

Compose reads optional overrides from the root `.env` (or the shell):

| Variable | Default | Purpose |
| --- | --- | --- |
| `FRONTEND_PORT` | `3000` | Frontend's localhost-only published port |
| `BACKEND_PORT` | `8080` | Backend's localhost-only published port; internal port stays 8080 |
| `DYNAMODB_PORT` | `8000` | DynamoDB Local's localhost-only published port |
| `DYNAMODB_TABLE_NAME` | `workflow-analyses` | Table created by the initializer and used by the backend |
| `AWS_REGION` | `us-east-1` | DynamoDB region; Compose uses dummy local credentials |
| `SPRING_PROFILES_ACTIVE` | `prod` | Spring profile, with graceful shutdown and health-only Actuator |
| `CORS_ALLOWED_ORIGINS` | empty | Optional comma-separated origins for direct cross-origin API access |
| `GITHUB_TOKEN` | empty | Backend-only GitHub token; GitHub routes return 503 until configured |
| `JAVA_TOOL_OPTIONS` | empty | Optional JVM flags, e.g. `-Xmx512m` |
| `VITE_API_BASE_URL` | `/` | Public build-time API base; keep `/` for the same-origin proxy |

Changing `VITE_API_BASE_URL` requires rebuilding the frontend. Other values are
runtime/Compose configuration; rerun `docker compose up -d` to apply changes.
The frontend's local `.env.*` files are excluded from Docker builds. Each
Dockerfile has a dedicated `.dockerignore` allowlist to exclude unrelated files,
host build output, local environment files, and private keys from its context.
There are no hardcoded application secrets. Credentials are required only for
GitHub API requests, not health checks, tests, or sample frontend data.
Do not put secrets in build arguments, `VITE_*` variables, images, or committed
environment files. Supply production credentials using an appropriate secret
store with backend environment injection rather than frontend configuration.

Stop local servers using these ports before starting Compose. Docker builds
use the repository root as their context, and the backend image build runs
its non-container backend tests. Repository integration tests require a Docker
daemon and run in host/CI `mvn -f backend/pom.xml verify`, not inside the image
build. Source changes require an image rebuild; this is not a hot-reload setup.

```sh
# Alternatively, build and start detached, waiting for both health checks:
docker compose up --build -d --wait
docker compose ps
curl --fail http://localhost:3000/healthz
curl --fail http://localhost:3000/api/health
curl --fail http://localhost:8080/actuator/health

# Inspect startup/API errors
docker compose logs backend frontend

# Stop and remove this application's containers and network
docker compose down
```

## Validation and CI

```sh
mvn -f backend/pom.xml verify
npm run typecheck
npm run build
npm run infra:synth
docker compose config --quiet
```

The root npm build compiles the frontend and infrastructure, **not** the Maven
backend. GitHub Actions runs independent backend verification, frontend build,
and CDK synthesis jobs on pushes and pull requests. The frontend can also be smoke-tested in a browser: navigate all five routes,
filter sample repositories and failures, resize to mobile and use the navigation
drawer, and verify health checking with the backend both running and stopped.
Health responses can be intercepted to verify non-UP and malformed payloads.
No automated frontend test framework is configured yet.

Keep credentials out of source control. Local `.env` files and CDK context are
ignored. The frontend's optional public API URL is documented above; no secrets
are required by this scaffold.
