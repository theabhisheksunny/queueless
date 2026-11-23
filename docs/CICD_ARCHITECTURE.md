# QueueLess — CI/CD Pipeline Architecture

This document details the production-grade Continuous Integration and Continuous Delivery (CI/CD) architecture implemented for **QueueLess** using GitHub Actions, Google Cloud Run, Cloudflare Workers, and Supabase PostgreSQL.

---

## 1. High-Level Architecture Overview

```
                      +----------------------------------------+
                      |           Developer Push / PR          |
                      +-------------------+--------------------+
                                          |
                        +-----------------+-----------------+
                        |                                   |
             [Feature Branch / PR]                   [Push to master]
                        |                                   |
                        v                                   v
             +--------------------+               +--------------------+
             |   ci.yml Workflow  |               |   cd.yml Workflow  |
             +--------------------+               +--------------------+
                        |                                   |
    +-------------------+-------------------+               |
    |                   |                   |               v
    v                   v                   v    +-----------------------+
[Policy Audit]    [Backend CI]       [Frontend CI]  | Master CI Gate (All)  |
(10 Policies)     (Java 21, 60 tests)(tsc, oxlint,  +-----------+-----------+
    |                   |             vite build)               | (Passes)
    +-------------------+-------------------+                   v
                        |                         +---------------------------+
                        v                         | Build Immutable Artifacts |
             [Playwright E2E Suite]               | - Docker: sha-${COMMIT_SHA}|
             (25 End-to-End Tests)                | - Frontend: dist bundle   |
                        |                         | - release-metadata.json   |
                        v                         +-------------+-------------+
             [Container Trivy Scan]                             |
             (Vulnerability Analysis)                           v
                                                  +---------------------------+
                                                  | Deploy to Staging         |
                                                  | - Cloud Run: staging      |
                                                  | - Cloudflare: env staging |
                                                  +-------------+-------------+
                                                                |
                                                                v
                                                  +---------------------------+
                                                  | Automated Staging Smoke   |
                                                  | (scripts/smoke_test.py)   |
                                                  +-------------+-------------+
                                                                |
                                                                v
                                                  +===========================+
                                                  |  MANUAL APPROVAL GATE     |
                                                  |  (GitHub Env: production) |
                                                  +===========================+
                                                                | (Approved)
                                                                v
                                                  +---------------------------+
                                                  | Deploy to Production      |
                                                  | - Cloud Run: production   |
                                                  | - Cloudflare: production  |
                                                  +-------------+-------------+
                                                                |
                                                                v
                                                  +---------------------------+
                                                  | Automated Prod Smoke Test |
                                                  | (scripts/smoke_test.py)   |
                                                  +---------------------------+
```

---

## 2. Pipeline Workflows

### 2.1. Continuous Integration (`.github/workflows/ci.yml`)

The CI workflow triggers automatically on:
- All Pull Requests targeting `master` or `main`.
- All pushes to feature branches (`**`).
- Manual trigger via `workflow_dispatch`.

It executes five parallel and staged quality gates:

1. **`security-policy-audit`**:
   - Executes [`scripts/verify_production_policies.py`](file:///D:/PROJECTS/QUEUELESS/scripts/verify_production_policies.py) verifying 10 critical deployment constraints.
   - Runs Gitleaks secret detection to prevent credential leakage.

2. **`backend-ci`**:
   - Java 21 (Eclipse Temurin) environment.
   - Maven dependency caching (`~/.m2/repository`).
   - Runs Trivy filesystem scan for vulnerable dependencies.
   - Executes all 60 backend JUnit, MockMvc, and security integration tests (`mvn clean test`).
   - Packages the Spring Boot JAR (`mvn package -DskipTests`).

3. **`frontend-ci`**:
   - Node.js 20.x environment with strict lockfile installation (`npm ci`).
   - Runs fast linter (`oxlint`).
   - Runs TypeScript strict compilation and Vite production build (`tsc -b && vite build`).
   - Detects unused dependencies via `depcheck`.

4. **`e2e-playwright-tests`**:
   - Runs after backend and frontend builds succeed.
   - Launches the compiled Spring Boot application in isolated background mode.
   - Probes `/actuator/health` until HTTP 200 `UP` status is confirmed.
   - Installs Playwright Chromium and executes all 25 end-to-end user journeys and regression tests.

5. **`container-security-scan`**:
   - Builds the production multi-stage Docker image locally.
   - Scans image with Trivy for `CRITICAL` and `HIGH` CVEs.

---

### 2.2. Continuous Delivery & Deployment (`.github/workflows/cd.yml`)

The CD workflow triggers on:
- Manual trigger via `workflow_dispatch` with deployment scope selection:
  - `staging_only`: Staging deployment & automated smoke tests.
  - `staging_and_production`: Staging deploy + smoke tests + cryptographic attestation + manual approval gate + deterministic Crane promotion + production deploy.

#### Security & Least-Privilege Permissions:
- Global `permissions: contents: read`.
- Deployment and artifact jobs explicitly require `id-token: write` to authenticate to GCP via **Workload Identity Federation (WIF)**, eliminating static JSON service account keys (`GCP_SA_KEY`).
- Dedicated service accounts per lifecycle stage (`GCP_STAGING_DEPLOYER_SA`, `GCP_PROMOTER_SA`, `GCP_PROD_DEPLOYER_SA`) eliminate monolithic admin accounts and shared service account fallbacks.
- Routine Cloud Run deployments do not require `roles/run.admin` or `--allow-unauthenticated`. Ingress policy (`allUsers` -> `roles/run.invoker`) is bound once during initial environment bootstrap.

#### Stage 1: Master CI Gate
Re-runs full 10-policy verification, unit/security tests, and Playwright E2E tests to ensure absolute branch health before building deployment artifacts.

#### Stage 2: Immutable Artifact Creation & Cryptographic Attestation
- Computes git commit SHA and short SHA (e.g. `c2df109`).
- Authenticates to GCP via Workload Identity Federation using `GCP_STAGING_DEPLOYER_SA`.
- Builds the multi-stage Docker container image from root `Dockerfile`.
- Tags container image with immutable tags:
  - `asia-south1-docker.pkg.dev/${GCP_PROJECT_ID}/queueless-staging/backend:${COMMIT_SHA}`
  - `asia-south1-docker.pkg.dev/${GCP_PROJECT_ID}/queueless-staging/backend:sha-${SHORT_SHA}`
- Pushes image to Staging Google Artifact Registry.
- Resolves the authoritative immutable image digest (`sha256:...`).
- Generates SLSA cryptographic build provenance attestation (`actions/attest-build-provenance`).
- Generates `release-metadata.json` recording commit SHA, author, timestamp, branch, and staging image digest.
- Bundles frontend dist assets and stores them as GitHub Actions artifacts.

#### Stage 3: Staging Deployment
- Validates downloaded frontend artifact layout (`frontend/dist/index.html`).
- Authenticates to GCP via Workload Identity Federation using `GCP_STAGING_DEPLOYER_SA`.
- Deploys backend image to Google Cloud Run staging service (`queueless-backend-staging`) in `asia-south1`.
- Configures dedicated staging runtime service account `queueless-staging-sa@${GCP_PROJECT_ID}.iam.gserviceaccount.com`.
- Injects non-sensitive environment configuration via `--set-env-vars="SPRING_PROFILES_ACTIVE=staging,APP_DATASOURCE_EXPECTED_ENV_TYPE=STAGING,APP_DATASOURCE_EXPECTED_PROJECT_REF=${QUEUELESS_STAGING_SUPABASE_PROJECT_REF}"`.
- Securely mounts credentials from Google Secret Manager via `--set-secrets="SPRING_DATASOURCE_URL=QUEUELESS_STAGING_DB_URL:latest,APP_JWT_SECRET=QUEUELESS_STAGING_JWT_SECRET:latest,APP_ADMIN_KEY=QUEUELESS_STAGING_ADMIN_KEY:latest,APP_SUPERADMIN_PASSWORD=QUEUELESS_STAGING_SUPERADMIN_PASS:latest,SPRING_FLYWAY_USER=QUEUELESS_STAGING_MIGRATOR_USER:latest,SPRING_FLYWAY_PASSWORD=QUEUELESS_STAGING_MIGRATOR_PASS:latest"`.
- Deploys frontend to Cloudflare Worker Staging environment without rebuilding (`wrangler deploy --env staging --no-build`).
- Executes [`scripts/smoke_test.py`](file:///D:/PROJECTS/QUEUELESS/scripts/smoke_test.py) against the staging endpoint.

#### Stage 4: Deterministic Release Promotion (Crane OCI Copy)
- Bound to GitHub Environment **`production-promotion`** with mandatory manual reviewer approval.
- Authenticates to GCP via Workload Identity Federation using `GCP_PROMOTER_SA`.
- Verifies cryptographic SLSA build provenance of the staging container image.
- Uses Crane OCI tool (`scripts/promote_image.py`) to copy the exact verified staging image byte-for-byte to the production repository (`asia-south1-docker.pkg.dev/${GCP_PROJECT_ID}/queueless-prod/backend`).
- Validates that source and destination SHA-256 digests match bit-for-bit without rebuilding or recompressing layers.

#### Stage 5: Production Deployment (Manual Approval Gate)
- Bound to GitHub Environment **`production`**.
- Native GitHub manual reviewer sign-off required before execution starts.
- Validates downloaded frontend artifact layout (`frontend/dist/index.html`).
- Authenticates to GCP via Workload Identity Federation using `GCP_PROD_DEPLOYER_SA`.
- Deploys the promoted immutable image (`@${VERIFIED_DIGEST}`) to production Cloud Run (`queueless-backend`) in `asia-south1`.
- Configures dedicated production runtime service account `queueless-prod-sa@${GCP_PROJECT_ID}.iam.gserviceaccount.com`.
- Injects non-sensitive config via `--set-env-vars="SPRING_PROFILES_ACTIVE=prod,APP_DATASOURCE_EXPECTED_ENV_TYPE=PRODUCTION,APP_DATASOURCE_EXPECTED_PROJECT_REF=${QUEUELESS_PROD_SUPABASE_PROJECT_REF}"`.
- Securely mounts production credentials from Google Secret Manager via `--set-secrets="SPRING_DATASOURCE_URL=QUEUELESS_PROD_DB_URL:latest,APP_JWT_SECRET=QUEUELESS_PROD_JWT_SECRET:latest,APP_ADMIN_KEY=QUEUELESS_PROD_ADMIN_KEY:latest,SPRING_FLYWAY_USER=QUEUELESS_PROD_MIGRATOR_USER:latest,SPRING_FLYWAY_PASSWORD=QUEUELESS_PROD_MIGRATOR_PASS:latest"`.
- Deploys frontend to Cloudflare Worker Production without rebuilding (`wrangler deploy --no-build`).
- Executes [`scripts/smoke_test.py`](file:///D:/PROJECTS/QUEUELESS/scripts/smoke_test.py) against production URL.

---

## 3. Strict Quality & Security Gates

```
+-----------------------------------------------------------------------------------+
| PRE-FLIGHT POLICY CHECKS (scripts/verify_production_policies.py)                  |
+-----------------------------------------------------------------------------------+
| [1]  No hardcoded production credentials (DB, JWT, Admin Key)                     |
| [2]  Demo owner account strictly isolated to non-production profiles              |
| [3]  Complete elimination of OTP/SMS tables, endpoints, and external SMS SDKs     |
| [4]  No mock auth/payment providers active in production source code              |
| [5]  Frontend client uses relative same-origin URLs without leaking localhost:8080|
| [6]  All mandatory environment variables defined with configurable fallbacks      |
| [7]  Zero exposed private keys (VAPID, RSA, EC) or tokens in tracked files        |
| [8]  Zero authentication bypasses in AuthService (strict BCrypt checks)           |
| [9]  CI/CD least-privilege permissions, WIF, and Secret Manager references        |
| [10] Clean test helpers and zero obsolete OTP methods                             |
+-----------------------------------------------------------------------------------+
```

---

## 4. Cost & Runner Optimization

1. **Concurrency Controls**:
   ```yaml
   concurrency:
     group: ci-${{ github.workflow }}-${{ github.ref }}
     cancel-in-progress: true
   ```
   Cancels obsolete builds on rapid successive commits to save GitHub Actions minutes.

2. **Dependency Caching**:
   - Maven repository cached across runs (`actions/setup-java` with `cache: 'maven'`).
   - Node modules cached across runs (`actions/setup-node` with `cache: 'npm'`).

3. **Cloud Run Single-Instance Autoscaling**:
   - Staging: `min-instances=0`, `max-instances=1` (zero idle costs, single instance concurrency).
   - Production: `min-instances=0`, `max-instances=1` (scales to zero during idle hours, strictly single instance when active).

4. **Zero Self-Hosted Infrastructure**: Runs entirely on GitHub-hosted `ubuntu-latest` runners without recurring server maintenance costs.

---

## 5. Cloud Run Single-Instance MVP Architecture & State Boundaries

QueueLess MVP is purposefully architected as a **single-instance service** on Google Cloud Run (`--max-instances=1`). This design decision provides critical architectural benefits while maintaining strict state consistency:

### 5.1. In-Memory State & Concurrency Guarantees
- **Active Queue State (`ActiveQueueManager`)**:
  - Live queue state for each shop (`ShopActiveQueue`) is maintained in concurrent memory structures inside the Spring Boot JVM for sub-millisecond token issuance, live position calculation, and zero database read pressure during active trading hours.
  - Setting `--max-instances=1` ensures that all customer actions (joining, cancelling, progressing) and owner controls (calling next, skipping, closing) route to the exact same JVM process, completely preventing race conditions and split-brain token sequencing without requiring external distributed cache infrastructure.
- **Server-Sent Events (SSE) Live Streams (`SseEmitterService`)**:
  - Customer live queue position updates and owner dashboards connect via persistent HTTP Server-Sent Events (`/api/queue/stream/{shopId}`).
  - In a single-instance architecture, all open SSE emitters reside within the same memory space as the event publisher (`QueuePushEventListener`), ensuring immediate and reliable delivery of push updates to connected clients without needing a distributed pub/sub broker.

### 5.2. System of Record & Cold-Start Recovery
- **Supabase PostgreSQL as Durable Source of Truth**:
  - All critical domain records—registered shops, daily sessions, customer token receipts, trial periods, subscription transactions, and category changes—are durably committed to PostgreSQL with Flyway-versioned relational tables.
- **Restart Rehydration**:
  - Upon cold start or Cloud Run revision deployment, `ActiveQueueManager` rehydrates active queue token lists and shop state from persistent PostgreSQL storage (`daily_sessions` and `customers` tables) upon first access.
  - Client web applications (React SPA) implement exponential reconnect logic for SSE streams to automatically recover connectivity following any Cloud Run instance transition.

### 5.3. Scalability Boundaries & Future Architecture Roadmap
- **MVP Operational Scope**:
  - A single Cloud Run container with 1 vCPU and 1 GiB memory comfortably serves thousands of daily active queue operations across hundreds of registered micro-retailers.
  - Horizontal multi-instance scaling (`--max-instances > 1`) is an explicit non-goal for the MVP release.
  - Scaling horizontally across multiple Cloud Run instances requires introducing a distributed pub/sub layer (such as Redis Pub/Sub or Google Cloud Pub/Sub) and shared session coordination. This is planned for future enterprise scale milestones and is deliberately omitted from the MVP to avoid unnecessary cost, operational complexity, and infrastructure overhead.
