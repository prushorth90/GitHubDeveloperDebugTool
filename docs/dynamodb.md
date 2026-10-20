# DynamoDB workflow analysis persistence

The backend uses AWS SDK for Java v2 and the DynamoDB Enhanced Client.
`WorkflowAnalysisRepository` owns table access; application startup never creates
or modifies tables. Health endpoints remain application-liveness checks.

## Environment

| Variable | Default | Meaning |
| --- | --- | --- |
| `DYNAMODB_ENDPOINT` | empty | Endpoint override, e.g. `http://localhost:8000`; leave unset in AWS |
| `AWS_REGION` | `us-east-1` | Region used by the SDK |
| `DYNAMODB_TABLE_NAME` | `workflow-analyses` | Existing table name |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | none | Standard SDK environment credentials |
| `AWS_SESSION_TOKEN` | none | Required alongside temporary environment credentials |
| `DYNAMODB_PROCESSING_LEASE` | `PT5M` | Positive ISO-8601 processing lease duration |

Credentials use the SDK default provider chain: environment credentials, profiles,
web identity, ECS/EC2 roles, etc. Prefer workload IAM roles in production. Dummy
credentials are **only** supplied by the development Compose stack or by the
developer's host environment. There is no dummy-credential fallback in Java.
Do not put production credentials in frontend variables, images or source control.
Maven does not automatically read `.env`.

### Development

```sh
docker compose up -d dynamodb-local localstack
docker compose run --rm dynamodb-init
docker compose run --rm s3-init
export DYNAMODB_ENDPOINT=http://localhost:8000
export AWS_REGION=us-east-1 AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local
mvn -f backend/pom.xml spring-boot:run
```

Alternatively, `docker compose up --build` configures the backend's internal
endpoint and dummy credentials automatically. The backend waits for the
initializer to succeed. `docker/dynamodb/create-tables.sh` retries readiness for
up to 30 attempts, creates an absent table and its GSI, waits for table existence,
and reports the resulting schema. Running it again preserves existing data.
Failures exit nonzero and prevent backend startup.

DynamoDB Local uses `-sharedDb` and a named volume; the local-only container runs
as root to write to that Docker-managed volume. `DYNAMODB_PORT` changes only the
host port. All published ports bind to loopback. These containers and dummy
credentials are not a production deployment. `docker compose down` retains data;
removing the database volume intentionally deletes development records.

### Production

Provision the following schema through your deployment tooling before starting
the backend; the CDK stack provisions the S3 log bucket, not the table. Use
`PAY_PER_REQUEST`, string attributes `pk`, `sk`, `failurePk`, `failureSk`, a
table key of `pk` (HASH) / `sk` (RANGE), and the `recent-failures` GSI described
below. The development script contains the equivalent `create-table` definition,
but is not a production migration tool. Existing incompatible tables require an
explicit schema migration, not automatic recreation.

Set `SPRING_PROFILES_ACTIVE=prod`, `AWS_REGION`, and `DYNAMODB_TABLE_NAME`;
**unset** `DYNAMODB_ENDPOINT`. Grant the backend role `dynamodb:GetItem` and
`dynamodb:PutItem` on the table, and `dynamodb:Query` on
`table/<name>/index/recent-failures`. It needs neither table creation nor scan
permissions. Configure encryption, backup/retention and access controls to suit
your deployment. Compose deliberately targets Local, even with the Spring `prod`
profile; deploy the backend separately for real AWS.

Analysis evidence may contain sensitive source/log excerpts. Full logs are stored
only as sanitized gzip objects in S3, referenced by `logObjectKey` in each
available failed-job analysis. Recognized credentials are redacted before
analysis/upload, and evidence lines are bounded to 2,000 characters plus a
truncation marker. Selected evidence, context, signatures and suggestions remain
in DynamoDB. See [S3 configuration and retention](../README.md#s3-job-log-storage).
Protect
the table and API accordingly; end-user authentication/authorization is not
provided by this change. Never expose the developer backend publicly without
an access-control boundary.

## Schema and access patterns

One item atomically stores one workflow run **attempt**. JSON string attributes
`workflowRun` and `analysis` preserve the typed DTOs without dropping fields:

- Workflow ID, name, run number, attempt, event, status/conclusion, branch/SHA,
  URL, repository metadata and GitHub timestamps.
- All failed/timed-out job metadata and steps, log availability, failure category,
  normalized signature, lowercase SHA-256 fingerprint, suspected cause,
  suggested investigation, confidence, evidence and context.
- Top-level processing `status`, `createdAt`, `updatedAt`, and, only during
  processing, `processingToken` and `leaseUntil`.

JSON is used for aggregate payloads; queried keys/state are native attributes.
Missing/expired logs are retained as failed jobs with null analysis, not invented
fingerprints. A completed run with no failed jobs is also retained.

| Access pattern | Keys / operation | Consistency |
| --- | --- | --- |
| Retrieve a specific attempt | `findRun(repository, runId, attempt)`; `GetItem` with `pk=REPO#<lowercase owner/repo>`, `sk=RUN#<20-digit run ID>#ATTEMPT#<10-digit attempt>` | Strong |
| Idempotently claim an attempt | Conditional `PutItem` on the same key | Atomic |
| List recent failed runs for a repository | `recentFailures(repository, limit)`; descending `Query` on `recent-failures` with `failurePk=REPO#<lowercase owner/repo>` | Eventual |

The sparse `recent-failures` GSI has string HASH key `failurePk`, string RANGE
key `failureSk`, and `ALL` projection. Only `COMPLETED` items with a nonempty
failed-job list contain its keys. Thus successful analyses, `PROCESSING` and
`FAILED` processing states never occupy the failure index. A processing failure
is distinct from a workflow/job failure.

`failureSk=<UTC workflow createdAt>#<base-table sk>`. Timestamps use fixed
millisecond precision (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`) for lexicographic chronology;
missing GitHub creation time falls back to the first processing timestamp.
Run ID/attempt break ties deterministically. Chronology means **workflow creation
time**, not analysis completion order. Rerun attempts are distinct records but
share GitHub's original creation time. The repository reads 1-100 recent records,
following DynamoDB pages as necessary; this is a bounded top-N operation, not a
public cursor-paginated API. No scan or filter expression is needed. GSI updates
can lag; use the strongly consistent point read to confirm an immediate write.

The run metadata plus serialized analysis must fit a **350 KiB UTF-8 payload
budget**, leaving room below DynamoDB's 400 KiB item limit for keys/attributes.
Oversized results fail explicitly without partial completion or truncation.
For workloads beyond this bound, split job items or offload payloads to object
storage before increasing supported workload size.

## Processing and retry semantics

1. The analysis endpoint first fetches GitHub run metadata (also on cache hits),
   validating access and requiring a completed run with a positive attempt.
2. An absent item is claimed as `PROCESSING` using
   `attribute_not_exists(pk)`, a random token and a lease deadline.
3. A `COMPLETED` record returns its original result without downloading jobs/logs
   again. This includes unavailable-log results; retries do not refresh them.
4. A `FAILED` record or an expired `PROCESSING` record may be conditionally
   reclaimed with a new token. Original `createdAt` is preserved.
5. Completion/failure requires `status=PROCESSING`, the matching token and an
   unexpired lease. A stale worker cannot overwrite another worker or a completed
   result, even if it finishes after takeover.
6. GitHub/analyzer/storage exceptions are propagated, not replaced with an empty
   success. The service attempts to mark owned work `FAILED` and preserves the
   original error if that write also fails. A crash or ambiguous network failure
   leaves work reclaimable after lease expiry; a committed result cannot be
   downgraded by a subsequent failure write.

Active/stale claim conflicts return HTTP 409 with `Retry-After: 30`. No automatic
processing retry, lease renewal, queue or background worker is introduced.
Configure the lease above your expected synchronous analysis duration (plus
storage/network time); a worker that exceeds it fails rather than committing
stale work. Distributed workers need reasonably synchronized clocks.
Reattempts fetch jobs from GitHub's attempt-specific endpoint, avoiding a race
with a newer rerun. Existing webhook reception remains authenticated and
log-only; its delivery-ID cache is independent of repository idempotency.

## Tests

Docker must be running. Tests use a disposable DynamoDB Local container, random
port and fresh table per test, with dummy credentials only. They do not use the
Compose volume, a developer's AWS credentials, or any real AWS resources.

```sh
# All backend tests, real repository integration tests, and packaging:
mvn -f backend/pom.xml verify

# Only repository integration tests:
mvn -f backend/pom.xml -Dtest=WorkflowAnalysisRepositoryIT test

# Non-container tests only:
mvn -f backend/pom.xml test
```

Coverage includes full-field round trips, missing records, run-attempt/repository
isolation, chronological limits and sparse-index membership, duplicate/concurrent
claims, failed/expired retry, stale completion/failure fencing, payload bounds,
missing-table errors, service cache reuse and service retry after upstream errors.
Tests fail rather than silently skip if Docker is unavailable. The image build
uses `-DskipITs=true` because a Docker daemon is not available inside build stages;
CI runs `verify` on the Docker-capable runner.
