# Architecture

This repository provides a developer dashboard and backend health API with
established component boundaries. It does not implement GitHub access, CI data
ingestion, analysis, storage, or AWS resources.

## Backend

All application packages live beneath `com.githubci.intelligence` so Spring
Boot's default component scanning finds controllers, services, and configuration.

| Package | Intended responsibility |
| --- | --- |
| `controllers` | HTTP routing and request/response handling |
| `services` | Application use cases and business logic |
| `repositories` | Persistence interfaces and adapters once storage is chosen |
| `models` | Domain objects and future persistence models |
| `dto` | API request and response contracts |
| `configuration` | Spring beans and runtime configuration |
| `exceptions` | Application exceptions and centralized HTTP error handling |

`HealthController` delegates to `HealthService`, which produces a `HealthResponse`
using the configured application name and an injectable UTC `Clock`.
`GlobalExceptionHandler` maps MVC, validation, and unexpected failures to the
`ApiError` JSON contract; unexpected failures are logged without exposing details.
`WebConfiguration` provides the clock and an environment-configured CORS allowlist
for `/api/**`. Local and production profiles configure CORS and Actuator exposure.
Repository and model packages remain empty until persistence is needed.

Tests cover the health contract with a fixed clock, JSON exception responses,
Jakarta Bean Validation, full-context endpoint wiring, CORS, and production
configuration without external services.

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

[The CDK app](../infrastructure/bin/github-ci-intelligence.ts) creates an empty,
environment-agnostic stack. Add resource definitions under
[`infrastructure/lib`](../infrastructure/lib) only when deployment requirements
are established. Compiled JavaScript and synthesized templates are generated
artifacts, not source files.

[`docker`](../docker) holds multistage frontend and backend Dockerfiles, dedicated
build-context ignore files, and Nginx configuration.
[`docker-compose.yml`](../docker-compose.yml) connects both services on a bridge
network and waits for backend health before starting Nginx. Nginx serves the SPA
and proxies `/api` requests unchanged to `backend:8080`; the browser never uses
Docker-internal hostnames. Both services have container health checks.
[`CI`](../.github/workflows/ci.yml) validates all three
components without AWS credentials or deployment permissions.

See the [root README](../README.md) for setup and execution commands.
