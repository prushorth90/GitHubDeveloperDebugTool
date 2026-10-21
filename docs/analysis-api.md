# Persisted workflow analysis API

**No user authentication or authorization is implemented. Keep the API bound to
loopback or behind a trusted, access-controlled network boundary until
authorization is implemented. Do not publish it on the Internet.** Repository
metadata, private repository names and sanitized log evidence are sensitive.
CORS is not an authorization boundary. The Compose ports bind to loopback; when
running directly, set `SERVER_ADDRESS=127.0.0.1` for local-only access.

All endpoints below read DynamoDB only, without GitHub, S3 or analysis calls.
`FailureService` owns validation, mapping, filtering, similarity and statistics;
future MCP adapters can call the same public methods without invoking HTTP.
Storage entities, leases, processing tokens and S3 object keys are not returned.

## Routes

`repositoryId` is the positive numeric **GitHub repository ID**, not `owner/repo`.
`runId` is the positive GitHub workflow run ID.

| GET route | Result in `data` | Query parameters |
| --- | --- | --- |
| `/api/repositories` | Page of repository DTOs | `limit`, `nextToken` |
| `/api/repositories/{repositoryId}/runs` | Page of run summaries, including processing/failed analyses and successful runs | `limit`, `nextToken`, `order`, `category` |
| `/api/repositories/{repositoryId}/failures` | Page of completed analyses with at least one failed/timed-out job | `limit`, `nextToken`, `order`, `category` |
| `/api/repositories/{repositoryId}/failures/{runId}` | Run summary and failed-job analysis DTOs | `attempt` |
| `/api/repositories/{repositoryId}/statistics` | Repository-wide attempt/job counts | none |
| `/api/repositories/{repositoryId}/failures/{runId}/similar` | Page of other run summaries with a matching stored fingerprint | `attempt`, `limit`, `nextToken`, `order`, `category` |

Examples:

```sh
curl 'http://localhost:8080/api/repositories?limit=20'
curl 'http://localhost:8080/api/repositories/1/runs?order=asc'
curl 'http://localhost:8080/api/repositories/1/failures?category=JAVA_COMPILATION'
curl 'http://localhost:8080/api/repositories/1/failures/42?attempt=1'
curl 'http://localhost:8080/api/repositories/1/statistics'
curl 'http://localhost:8080/api/repositories/1/failures/42/similar'
```

### Pagination, order and filtering

- `limit` defaults to 20, accepts 1-100, and bounds the number of DynamoDB
  records examined per request. DynamoDB's 1 MiB page bound may shorten a page.
- Successes use `{"data": ...}`. Lists use
  `{"data":{"items":[...],"nextToken":"..."}}`; a null `nextToken` means done.
- Pass `nextToken` unchanged, URL-encoded, with the same endpoint, repository,
  order, category and source attempt. Changing `limit` is allowed. Tokens are
  opaque continuation state, not credentials; malformed or mismatched tokens
  are rejected with 400.
- Category and similarity filtering happen **within** a bounded database page.
  A page can be empty and still have a continuation token. Clients must keep
  following tokens, not stop at an empty `items` array.
- Runs/failures/similar default to `order=desc` (newest first); `asc` is supported.
  Chronology uses GitHub workflow creation time, falling back to first
  persistence time. Run ID and attempt break ties. Attempts are separate records.
  Repository discovery is ordered by numeric repository ID.
- `category` is an exact, case-sensitive `FailureCategory` enum value:
  `JAVA_COMPILATION`, `MAVEN_DEPENDENCY`, `JUNIT_TEST`, `POSTGRESQL_CONNECTION`,
  `DOCKER_BUILD`, `AUTHENTICATION`, `GITHUB_ACTIONS_CONFIGURATION`, `UNKNOWN`.
  A run matches if any available job analysis has that category. Unavailable
  analyses are not assigned an invented `UNKNOWN` category.
- Similarity means any exact, nonblank stored fingerprint matches within the
  same repository. It excludes **all attempts** of the source run; candidates
  retain distinct attempts. Category filtering additionally applies to candidate
  runs. No approximate/semantic matching or cross-repository lookup is performed.

### Detail, incomplete analyses and errors

Without `attempt`, detail/similar select the latest persisted attempt using a
strongly consistent table query. An explicit attempt uses a strongly consistent
point read. If a newer attempt is still processing, the default detail returns
409 rather than silently returning an older completed result.

Run summaries include `analysisStatus` (`PROCESSING`, `FAILED`, `COMPLETED`) and
`analysisComplete`. Completion of processing does not guarantee that every job
had logs. A completed partial result returns 200, `analysisComplete=false`, and
the affected job's `logStatus`, `logMessage`, and null `analysis`. Similarity
returns 409 if no fingerprints are available; if some are available, only those
are used. A completed run with zero failed jobs is available in `/runs` but its
failure detail returns 404.

Errors retain the application's standard `ApiError` format:

```json
{
  "timestamp": "2026-10-20T07:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Workflow run not found",
  "path": "/api/repositories/1/failures/42"
}
```

| Status | Meaning |
| --- | --- |
| 400 | Invalid ID, attempt, category, order, limit or pagination token |
| 404 | Repository/run not persisted, or requested run has no failed jobs |
| 409 | Analysis still processing/failed, or no fingerprints available for similarity |
| 500 | Corrupt/incomplete stored completed analysis; details logged server-side |
| 503 | DynamoDB SDK/service failure, with `Retry-After: 30`; no empty-success fallback |

### Statistics and consistency

Statistics count **attempts**, not distinct run IDs:
`totalRuns`, `completedAnalyses`, `processingAnalyses`, `failedAnalyses`,
`failureRuns`, and `unavailableJobAnalyses`. `failuresByCategory` counts available
**job analyses**, including zeros for every category. A run with multiple failed
jobs contributes once to `failureRuns` and once per available job to categories.

Catalog discovery and detail reads are strongly consistent. Chronological
lists, failure lists, similarity, and statistics use eventually consistent GSIs.
They may lag recent writes. Cursors are not snapshots; concurrent updates can
change later pages. Statistics stream all repository pages (bounded memory, no
table scan) and are not a transactionally consistent snapshot. Their latency and
read cost grow with repository history; large deployments should introduce
maintained aggregates before using statistics on a high-frequency dashboard.

Statistics also include `latestRun` (the same run-summary DTO as `/runs`, or
null for an empty repository) and `lastAnalyzedAt` (the maximum completion
timestamp across completed analyses, or null if none have completed). The latest
run follows workflow creation chronology and can still be processing or failed;
its `analyzedAt` is not necessarily the repository's `lastAnalyzedAt`. Both fields
are computed during the existing statistics traversal with no additional reads.
The dashboard fetches statistics only for visible repositories, caches them for
five minutes, and refreshes only on explicit user action or a stale mount.

## Deployment and existing data

Provision the read schema and backfill existing analyses **before** enabling
these endpoints. See [DynamoDB schema and migration](dynamodb.md#read-api-schema-and-migration).
Neither API startup nor normal requests create indexes, scan tables or silently
fall back to expensive scans. A fresh empty catalog returns an empty repository
list; missing repositories return 404. A catalog entry is created when an
analysis is claimed, so a tracked repository can temporarily have no indexed
runs after a failed write or while the GSI catches up.

## Tests

```sh
# Controller/service unit tests:
mvn -f backend/pom.xml -Dtest=RepositoryControllerTest,FailureServiceTest test
# Actual DynamoDB + MVC/service integration for all six routes:
mvn -f backend/pom.xml -Dtest=WorkflowAnalysisRepositoryIT test
# Complete regression suite, including container integration tests:
mvn -f backend/pom.xml verify
```

The integration tests use an isolated DynamoDB Local container and temporary
tables, not developer or production data.
