# Manual diagnostic failure workflows

These workflows deliberately fail using real tools. They are isolated from the
application: no repository checkout, production tests, database service,
credentials, secrets, cloud SDKs, or production endpoints are used. Each workflow
has only `workflow_dispatch`, no token permissions, and no `continue-on-error`.
Existing CI and the echo-based test workflow are unchanged.

## Workflows and expected evidence

| Workflow | Actual operation | Expected evidence | Expected Java classification |
| --- | --- | --- | --- |
| [Diagnostic Test - PostgreSQL Connectivity](../.github/workflows/diagnostic-postgresql.yml) | A real `psql` client attempts `SELECT 1` on `127.0.0.1:65432` inside a network-disabled, disposable container with no server | `psql: error: connection to server at "127.0.0.1", port 65432 failed: Connection refused`; nonzero client exit (normally 2) | `POSTGRESQL_CONNECTION` / `HIGH` |
| [Diagnostic Test - Java Compilation](../.github/workflows/diagnostic-java-compilation.yml) | JDK 21 `javac` compiles a class containing undefined `missingValue` | `DiagnosticCompilation.java:3: error: cannot find symbol`, `symbol: variable missingValue`; nonzero compiler exit | `JAVA_COMPILATION` / `HIGH` |
| [Diagnostic Test - JUnit Assertion](../.github/workflows/diagnostic-junit.yml) | Maven Surefire runs JUnit Jupiter `assertEquals(200, 503, ...)` | `org.opentest4j.AssertionFailedError`, expected `200` versus actual `503`, failing method/source reference, `Tests run: 1, Failures: 1`, Maven test failure | `JUNIT_TEST` / `HIGH` |

Tool versions can change punctuation or formatting; verify the actual cited
diagnostic, not just a literal substring. Setup failures (image download,
JDK setup, Maven resolution, or compilation of the JUnit fixture) do not count as
a successful diagnostic exercise. Inspect which step failed.

The PostgreSQL client image is pulled from Docker Hub, but no external database
is contacted. `--network none` restricts the client to its own container loopback;
no ports or host directories are mounted and no server is started. The local
test username is not a credential for any real database. A short connect timeout
and `--no-password` prevent an interactive wait.

Java and JUnit source are generated only under the runner's temporary directory.
Paths use the runner-provided `RUNNER_TEMP` environment variable inside Bash
steps. The `runner` expression context is not available in job-level `env`;
these workflows avoid that expression context entirely. Each execution step
resolves its own temporary path rather than relying on shell variables surviving
between steps.
The JUnit workflow uses one test dependency (`junit-jupiter`) and pinned compiler
and Surefire plugins; Maven downloads their normal transitive artifacts. It
does not need Spring Boot, Docker services, or a production application build.
The test is compiled separately so a setup/compilation error is distinguishable
from the intended assertion failure.

## Triggering manually

1. Review the new files. With approval, commit and push the workflows to the
   default branch (`main`) so GitHub recognizes their manual triggers.
   Publishing them may run existing push-triggered production CI; the new
   diagnostic workflows themselves have no push or pull-request triggers.
2. Ensure the intended backend build, ngrok destination, webhook subscription,
   SQS consumer, and AWS SSO session/Bedrock configuration are ready.
3. In GitHub **Actions**, choose one of the three names above.
4. Select **Run workflow**, choose the intended branch, and confirm. Run one at
   a time initially to make correlation easy.
5. Record the new run ID and attempt. Confirm the intended tool-execution step
   failed and the workflow conclusion is `failure`.

Do not rerun the old completed echo-only analysis expecting its stored record to
change. A new run or attempt gets its own immutable analysis record.

## Evaluating the pipeline

Running these workflows is free of AWS commands, but their completion webhook
can cause the already-enabled backend to invoke paid Bedrock analysis. Dispatch
only when ready to authorize that processing.

For each new run:

1. Confirm the GitHub webhook delivery has event `workflow_run`, action
   `completed`, the correct run ID/attempt, and HTTP 204 from the application.
2. Correlate backend/SQS activity with that run ID. `COMPLETED` means processing
   and persistence finished, not that Bedrock necessarily succeeded.
3. In React **Workflow Failures**, refresh the repository and open the exact
   run/attempt. Alternatively read the persisted DTO without regenerating it:

   ```sh
   curl --fail --silent --show-error \
     'http://localhost:8080/api/repositories/1421755810/failures/NEW_RUN_ID?attempt=1'
   ```

4. Compare the Java category and classification confidence with the table.
   Check citations point to the actual tool output and original sanitized log
   line numbers, not just displayed shell source.
5. Check `analysisSource=BEDROCK`, the configured `modelIdentifier`, and
   `fallbackReason=null`. Java owns classification; Bedrock adds explanatory
   findings without changing category/confidence/fingerprint.
6. Evaluate explanation accuracy independently:
   - PostgreSQL: a real client connection was refused at the isolated local
     target. This does **not** establish a production outage, bad credentials,
     or the state of any external database.
   - Java: the missing symbol is a compiler observation. It does not establish
     a dependency-resolution or runtime database failure.
   - JUnit: the expected/actual mismatch and test location are observed facts;
     a failing fixture does not establish a production application bug.
   - Suggested causes remain unverified hypotheses with LOW finding confidence.
     HIGH observation confidence means the cited text exists, not that a
     model-inferred root cause is confirmed.

If a job uses Java fallback, classification may still be correct, but that run
does not verify Bedrock explanation accuracy. Inspect the structured warning's
stage/reason and retain the failed run for comparison. Do not weaken validation
or delete completed DynamoDB/ledger records to force a successful result.
