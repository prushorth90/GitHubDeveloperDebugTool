# DynamoDB workflow analysis persistence

The backend uses AWS SDK for Java v2 and the DynamoDB Enhanced Client.
`WorkflowAnalysisRepository` owns result access; `WorkflowEventRepository` owns
the ID-keyed queue processing ledger in the same table. Application startup never creates
or modifies tables. Health endpoints remain application-liveness checks.

## Environment

| Variable | Default | Meaning |
| --- | --- | --- |
| `DYNAMODB_ENDPOINT` | empty | Endpoint override, e.g. `http://localhost:8000`; leave unset in AWS |
| `AWS_REGION` | `us-east-1` | Region used by the SDK |
| `DYNAMODB_TABLE_NAME` | `workflow-analyses` | Existing table name |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | none | Standard SDK environment credentials |
| `AWS_SESSION_TOKEN` | none | Required alongside temporary environment credentials |
| `DYNAMODB_PROCESSING_LEASE` | `PT5M` | ISO-8601 lease duration, at least 30 seconds for event processing |

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
up to 30 attempts, creates an absent table and its GSIs, adds a missing
`repository-runs` index to existing development tables, waits for table existence
and the read index to become active, and reports the resulting schema.
Running it again preserves existing data.
Failures exit nonzero and prevent backend startup.

DynamoDB Local uses `-sharedDb` and a named volume; the local-only container runs
as root to write to that Docker-managed volume. `DYNAMODB_PORT` changes only the
host port. All published ports bind to loopback. These containers and dummy
credentials are not a production deployment. `docker compose down` retains data;
removing the database volume intentionally deletes development records.

### Production

Provision the following schema through your deployment tooling before starting
the backend; the CDK stack provisions the S3 log bucket, not the table. Use
`PAY_PER_REQUEST`, string attributes `pk`, `sk`, `failurePk`, `failureSk`, `runSk`, a
table key of `pk` (HASH) / `sk` (RANGE), and the `recent-failures` GSI described
below, plus the `repository-runs` GSI in the read schema below.
The development script contains the equivalent `create-table` definition,
but is not a production migration tool. Existing incompatible tables require an
explicit schema migration, not automatic recreation.

Set `SPRING_PROFILES_ACTIVE=prod`, `AWS_REGION`, and `DYNAMODB_TABLE_NAME`;
**unset** `DYNAMODB_ENDPOINT`. Grant the backend role `dynamodb:GetItem` and
`dynamodb:PutItem`, `dynamodb:UpdateItem`, and `dynamodb:Query` on the table,
and `dynamodb:Query` on `table/<name>/index/recent-failures` and
`table/<name>/index/repository-runs`. It needs neither table creation nor scan
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
public cursor-paginated API. The separate read API adds cursor pagination as
described below. No scan or filter expression is needed. GSI updates
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

Active/stale claim conflicts return HTTP 409 with `Retry-After: 30` for the manual
analysis endpoint. Analysis renews its lease between pages/failed jobs and before
completion. Configure the lease above the longest single job download/upload/
analysis operation; exceeding it fails rather than committing stale work.
Distributed workers need reasonably synchronized clocks.

Failed-workflow webhooks publish to SQS. A separate ledger item in this table uses
`pk=REPOSITORY#<repository-id>#RUN#<run-id>#ATTEMPT#<attempt>`, `sk=EVENT`, independent
of repository name or GitHub delivery ID. It stores `status` (`PROCESSING`,
`FAILED`, `COMPLETED`), `processingAttempts`, numeric epoch-millisecond
`createdAt`/`updatedAt`/`leaseUntil`, and an ownership token. It has no GSI keys or
automatic TTL. Conditional `UpdateItem` claims/renewals/terminal writes prevent
concurrent or stale event workers from completing another owner's work.
These items are not result DTOs and are never returned by result queries.

The event worker renews ownership/visibility every five seconds, and only marks
the ledger complete after the result is durable. A retry after result persistence
but before event completion reuses the result; a retry after event completion
skips all GitHub/S3 work. A crash before completion allows takeover after expiry.
The worker retrieves metadata and jobs for the event's exact attempt, avoiding a
race with a newer rerun. See [SQS operation and recovery](../README.md#asynchronous-worker-retries-and-recovery)
for acknowledgement, backoff, DLQ, IAM and local test details.

## Read API schema and migration

The [persisted analysis API](analysis-api.md) adds:

- Catalog items: `pk=REPOSITORIES`,
  `sk=REPOSITORY#<20-digit GitHub repository ID>`, with `repositoryDetails`
  containing repository metadata JSON. Claims upsert this metadata. These items
  are queried directly, not scanned, and have no GSI sort keys.
- A sparse `repository-runs` GSI: string HASH key `pk`, string RANGE key `runSk`,
  `ALL` projection. Every workflow analysis attempt, regardless of status, has
  `runSk=<UTC workflow createdAt>#<base-table sk>` using the same millisecond
  timestamp format and fallback as `failureSk`. Event ledger and catalog items
  do not occupy this index.
- Strongly consistent base-table prefix queries to select the latest persisted
  attempt of a run. Existing per-attempt keys and lease fencing are unchanged.

Normal reads require `Query` on the table and both indexes, in addition to the
existing item permissions. No normal read uses `Scan`; category/similarity
filtering is applied within a bounded query page. Statistics traverse all pages
of a single repository index partition.

For an **existing production table**, deploy the new index using your schema
management tooling, equivalent to:

```sh
aws dynamodb update-table --table-name "$DYNAMODB_TABLE_NAME" \
  --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=runSk,AttributeType=S \
  --global-secondary-index-updates \
  '[{"Create":{"IndexName":"repository-runs","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"},{"AttributeName":"runSk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}}]'
```

Skip creation if already present. Wait until the index is `ACTIVE` (check
`describe-table`, not just table existence). The Local initializer creates both
indexes on new tables and adds this index to older development tables; it does
not backfill application attributes.

Then perform an explicit maintenance backfill:

1. Back up the table and stop **all** analysis writers/workers; do not route read
   traffic to the new API yet.
2. Temporarily grant the maintenance role `dynamodb:Scan` along with item write
   permissions. This permission is not required by the normal backend role.
3. Build the backend and run it in non-web maintenance mode using the intended
   table/region/credentials (and endpoint override only for Local):

   ```sh
   mvn -f backend/pom.xml -DskipTests package
   java -jar backend/target/github-ci-intelligence-backend.jar \
     --spring.main.web-application-type=none \
     --app.sqs.worker-enabled=false \
     --app.dynamodb.backfill-read-metadata=true
   ```

4. Confirm the logged backfilled count and successful exit, and verify catalog
   and chronological query visibility after GSI propagation.
5. Remove the maintenance permission/property and restart normally with the new
   writer version before enabling read traffic.

Backfill explicitly scans existing `REPO#...` / `RUN#...` items, ignores event
ledger/catalog items, writes repository metadata, and updates **only** `runSk`.
It preserves payloads, state, leases, ownership and existing timestamps. It is
idempotent, fails explicitly on invalid stored data/database errors, and can be
rerun after a partial failure. It is not automatically enabled by startup and
must not run concurrently with writers. Without backfill, legacy attempts lack
catalog/index metadata and will not be visible through the new discovery/run
routes. Normal startup does not create, modify or validate the table schema.

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
