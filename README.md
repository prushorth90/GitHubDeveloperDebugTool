# GitHub CI Intelligence

A full-stack developer tool scaffold. No CI analysis, GitHub integration,
authentication, database, or business API is implemented yet.

## Technology

- **Backend:** Java 21, Spring Boot 4.1.1, Maven.
- **Frontend:** React 19, TypeScript 5.9, Vite 8, Material UI 9 with Emotion.
- **Infrastructure:** AWS CDK v2 in TypeScript, with an empty, environment-agnostic stack.
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
├── docker/                     # Dockerfiles, nginx, Compose
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
- Docker with Compose v2, only for containerized development.
- AWS credentials and an AWS account are **not** needed to synthesize the
  current empty stack. They will be needed for future deployments.

All commands below run from the repository root unless stated otherwise.

```sh
# Optional, if using nvm
nvm use

# Install both JavaScript workspaces; do not maintain separate lockfiles.
npm ci
```

## Run the backend

```sh
mvn -f backend/pom.xml spring-boot:run
```

The server listens on `http://localhost:8080`; set `PORT` to change it.
No custom endpoints exist, so requesting `/` returns HTTP 404 by design.
Stop with Ctrl+C.

```sh
# Compile, run the application-context smoke test, and package
mvn -f backend/pom.xml verify

# Run the packaged application
java -jar backend/target/github-ci-intelligence-backend.jar
```

No persistence driver, JPA, Lombok, or authentication library is included.

## Run the frontend

```sh
npm run dev
```

Open `http://localhost:5173` for the static Material UI starter page.
The frontend does not call the backend yet; no proxy or CORS policy is
configured before an API is defined.

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
`infrastructure/cdk.out/`. The stack deliberately contains **no AWS resources**;
there is nothing to deploy at this stage. Lookups are disabled during synthesis
so it can run without AWS access. A global CDK installation is not required.
CDK may emit a template-validation warning because CloudFormation requires a
non-empty `Resources` section for deployment; this is expected for this scaffold.

For future resource-bearing stacks, the workspace also provides:

```sh
npm run diff --workspace infrastructure
npm run deploy --workspace infrastructure
```

Before deployment, configure your AWS account/region and credentials, review
the generated resources and costs, and bootstrap the target environment as
needed. These commands may access or modify AWS; CI never deploys.

## Docker

```sh
docker compose -f docker/compose.yaml up --build
```

- Frontend: `http://localhost:3000` (production assets served by nginx).
- Backend: `http://localhost:8080` (Java 21 runtime).
- Both published ports bind only to the local machine.

Stop local servers using these ports before starting Compose. Docker builds
use the repository root as their context, and the backend image build runs
its smoke test. Source changes require an image rebuild; this is not a
hot-reload setup.

```sh
docker compose -f docker/compose.yaml down
```

## Validation and CI

```sh
mvn -f backend/pom.xml verify
npm run typecheck
npm run build
npm run infra:synth
docker compose -f docker/compose.yaml config --quiet
```

The root npm build compiles the frontend and infrastructure, **not** the Maven
backend. GitHub Actions runs independent backend verification, frontend build,
and CDK synthesis jobs on pushes and pull requests. Frontend and infrastructure
behavioral test frameworks are intentionally deferred until functionality exists.

Keep credentials out of source control. Local `.env` files and CDK context are
ignored; no application-specific secrets or environment variables are required
by this scaffold.
