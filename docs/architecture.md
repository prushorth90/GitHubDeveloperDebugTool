# Architecture

This repository establishes component boundaries only. It does not implement
GitHub access, CI data ingestion, analysis, storage, or AWS resources.

## Backend

All application packages live beneath `com.githubci.intelligence` so Spring
Boot's default component scanning will find future implementations.

| Package | Intended responsibility |
| --- | --- |
| `controllers` | HTTP routing and request/response handling |
| `services` | Application use cases and business logic |
| `repositories` | Persistence interfaces and adapters once storage is chosen |
| `models` | Domain objects and future persistence models |
| `dto` | API request and response contracts |
| `configuration` | Spring beans and runtime configuration |
| `exceptions` | Application exceptions and centralized HTTP error handling |

These packages are empty intentionally. The only test verifies that the Spring
application context starts without external services.

## Frontend

| Folder | Intended responsibility |
| --- | --- |
| `components` | Reusable presentation components |
| `pages` | Page-level composition; currently a static landing page |
| `services` | Future typed API clients |
| `hooks` | Reusable React hooks |
| `types` | Shared TypeScript contracts |
| `layouts` | Shared application shell |

The application uses Material UI's theme and CSS baseline. No router, state
management library, HTTP client library, or component test framework is needed
for the initial single-page scaffold.

## Infrastructure and operations

[The CDK app](../infrastructure/bin/github-ci-intelligence.ts) creates an empty,
environment-agnostic stack. Add resource definitions under
[`infrastructure/lib`](../infrastructure/lib) only when deployment requirements
are established. Compiled JavaScript and synthesized templates are generated
artifacts, not source files.

[`docker`](../docker) holds independent frontend and backend images plus local
Compose orchestration. [`CI`](../.github/workflows/ci.yml) validates all three
components without AWS credentials or deployment permissions.

See the [root README](../README.md) for setup and execution commands.
