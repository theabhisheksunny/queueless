# QueueLess — Render Free Backend Deployment Runbook

> **Target Architecture:** Render Free Web Service (Docker Backend) + Cloudflare Workers (Frontend SPA Reverse Proxy) + Supabase PostgreSQL (Managed Database)
> **Cost Profile:** $0.00 / month (100% Free Tiers across all tiers)
> **Operational Status:** Primary backend deployment target with Cloud Run retained as active fallback.

---

## 1. Executive Summary & Architecture Overview

QueueLess uses a decoupled architecture optimized for zero-cost operation on trusted cloud tiers:
- **Frontend SPA:** Hosted on Cloudflare Workers (`https://queueless.theabhisheksunny.workers.dev`). Cloudflare Worker acts as both the static file host and an edge reverse proxy forwarding all `/api/*` requests to the backend.
- **Backend Application:** Deployed on **Render Free Web Service** using the existing multi-stage `Dockerfile`. Runs Spring Boot 3.3.5 on Eclipse Temurin Java 21.
- **Database:** Supabase Managed PostgreSQL 16 using the **Session Pooler** (`pooler.supabase.com:5432`).
- **Cloud Run Status:** Retained as an inactive fallback in `.github/workflows/cd.yml`. Can be activated at any time if traffic demands exceed Render Free capacity.

```
[ User Browser / Device ]
           │
           ▼
[ Cloudflare Worker (Frontend & Edge Proxy) ]
   ├── Static Assets (React SPA, PWA, Assets)
   └── /api/* Proxying ───┐
                          ▼
            [ Render Free Web Service ]
            ├── Docker (Java 21 Spring Boot)
            ├── 512 MB RAM / 0.1 vCPU
            ├── Inactivity Sleep (15 min)
            ├── Health Check: /actuator/health
            └── Ephemeral Filesystem
                          │
                          ▼ (Port 5432, SSL Required)
            [ Supabase PostgreSQL 16 ]
            ├── Session Pooler (aws-0-ap-south-1.pooler.supabase.com:5432)
            ├── Flyway Migrations (queueless_flyway_prod)
            └── App Runtime Connections (queueless_app_prod)
```

---

## 2. Render Free Operational Profile & Technical Mitigations

Render Free provides 512 MB RAM and shared CPU with automatic sleep after 15 minutes of inactivity. QueueLess is engineered specifically to operate reliably within these parameters:

### 2.1 Memory Optimization (512 MB cgroup Limit)
By default, the JVM might calculate max RAM based on host physical memory if cgroup limits are not properly respected, causing an immediate SIGKILL (exit code 137).
- **Enforced Flags:**
  ```bash
  JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=65.0 -XX:InitialRAMPercentage=40.0 -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:+ExitOnOutOfMemoryError"
  ```
- **Budget Breakdown:**
  - Max Heap (65%): ~332 MB
  - Max Metaspace: 128 MB
  - Reserved JIT Code Cache: 48 MB (prevents HotSpot from expanding default 240 MB off-heap cache)
  - Thread Stacks (`-Xss512k`): ~25 MB (for ~50 threads)
  - Native memory & glibc headroom: ~20 MB
  - Total allocation strictly fits within the 512 MB cgroup envelope.

> [!NOTE]
> **Staging Acceptance Requirement:**
> While these JVM memory flags provide strict boundaries, JVM flags alone cannot physically guarantee complete immunity against cgroup OOM termination under unpredictable off-heap native memory spikes. Because local Docker daemon execution was unavailable during local pre-commit verification, **actual 512 MB container runtime testing in a live staging container remains a mandatory staging acceptance gate**.

### 2.2 Spin-Down & Cold Start Handling
- **Sleep Mechanism:** If no HTTP requests are received for 15 minutes, Render spins down the container to zero instances.
- **Wake-up Trigger:** Any incoming HTTP request wakes the container. Cold start takes approximately 30–50 seconds.
- **Cloudflare Edge Proxy Behavior:** Cloudflare Worker holds the client connection open while waiting for the upstream Render response.
- **Frontend User Experience:** The QueueLess frontend includes automatic retry and reconnect logic for failed initial queries, displaying standard loading skeleton states.
- **SSE Stream Auto-Wakeup:** The frontend customer tracking and admin dashboard pages implement automatic SSE reconnection with exponential backoff. An active user session streams keepalive heartbeats every 25 seconds (`SseEmitterService`), which prevents sleep while users are actively monitoring a queue.

### 2.3 Scheduled Maintenance Catch-Up (`QUEUELess_CLEANUP_ON_STARTUP`)
- **Problem:** QueueLess runs daily data lifecycle purges (expired challenges, stale idempotency tokens, inactive push subscriptions) at `03:00 AM UTC/IST` via `@Scheduled(cron = "${queueless.cleanup.cron:0 0 3 * * *}")`. If the Render Free instance is asleep at 3:00 AM, the scheduled cron will not fire.
- **Solution:** `QUEUELess_CLEANUP_ON_STARTUP=true` activates an asynchronous catch-up in `DataLifecycleMaintenanceService` via `@EventListener(ApplicationReadyEvent.class)`.
- **Concurrency & Safety:** The purge uses PostgreSQL advisory locks (`pg_try_advisory_lock(882371946251)`). If multiple instances spin up, only one instance acquires the lock. Startup is never blocked because the task runs asynchronously in `CompletableFuture.runAsync()`.

### 2.4 Ephemeral Storage
Render Free instances have ephemeral local filesystems that are wiped on redeploy or restart.
- QueueLess stores **zero persistent application state** on disk.
- All tokens, business branches, active queues, device authentications, and audit entries reside in Supabase PostgreSQL.

---

## 3. Database Connectivity & Security Requirements

QueueLess enforces strict database isolation and security boundaries before running any migrations or processing requests.

### 3.1 Session Pooler (Port 5432) is Mandatory
> [!CAUTION]
> You **MUST** connect to Supabase via the **Session Pooler on port 5432**, **NEVER** the Transaction Pooler (port 6543).
>
> QueueLess uses PostgreSQL session-scoped advisory locks (`pg_try_advisory_lock` / `pg_advisory_unlock`) for distributed synchronization and data lifecycle maintenance. Transaction poolers route subsequent statements to different server backends, breaking session locks and causing pooler errors.

- **Session Pooler JDBC URL:**
  ```
  jdbc:postgresql://<SUPABASE_POOLER_HOST>:5432/postgres?sslmode=require&prepareThreshold=0
  ```
  *(Example host: `aws-0-ap-south-1.pooler.supabase.com`)*

### 3.2 Role Separation
In accordance with production security policies, Flyway migrations and runtime application connections use distinct database credentials:
1. `SPRING_FLYWAY_USER`: Dedicated schema owner role (e.g., `queueless_flyway_prod` or `postgres.<project-ref>`).
2. `SPRING_DATASOURCE_USERNAME`: Least-privilege application role (e.g., `queueless_app_prod`).

### 3.3 Database Isolation Verification Canary
The pre-migration gate (`DatabaseIsolationVerificationCallback`) executes prior to Flyway. It verifies:
- `APP_DATASOURCE_EXPECTED_ENVIRONMENT_TYPE=PRODUCTION`
- `APP_DATASOURCE_EXPECTED_PROJECT_REF=<your-production-supabase-ref>`
- The presence of the canary table `_system_environment_canary` with matching environment attributes.

If the backend inadvertently connects to a staging or local database while in `prod` profile, the application **fails closed immediately** before executing any DDL or DML.

---

## 4. Environment & Secrets Mapping Guide

The following configuration variables must be provided in the Render Dashboard under **Environment Variables**:

| Variable Name | Required | Default / Value | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Yes | `prod` | Activates production profile and strict security gates |
| `JAVA_OPTS` | Yes | `-XX:+UseContainerSupport -XX:MaxRAMPercentage=65.0 -XX:InitialRAMPercentage=40.0 -Xss512k -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError` | JVM memory limits for 512 MB cgroup |
| `QUEUELESS_CLEANUP_ON_STARTUP` | Yes | `true` | Triggers async catch-up purge on container wake-up |
| `APP_CORS_ALLOWED_ORIGINS` | Yes | `https://queueless.theabhisheksunny.workers.dev` | Allowed CORS origins for frontend |
| `APP_DATASOURCE_EXPECTED_ENVIRONMENT_TYPE` | Yes | `PRODUCTION` | Target environment type verification gate |
| `APP_DATASOURCE_EXPECTED_PROJECT_REF` | Yes | *(Supabase Project Ref)* | Supabase 20-character project identifier |
| `SPRING_DATASOURCE_URL` | Yes | `jdbc:postgresql://<host>:5432/postgres?sslmode=require&prepareThreshold=0` | Supabase Session Pooler JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Yes | `queueless_app_prod` | Application runtime database user |
| `SPRING_DATASOURCE_PASSWORD` | Yes | *(Database Password)* | Application runtime database password |
| `SPRING_FLYWAY_USER` | Yes | `queueless_flyway_prod` | Dedicated Flyway migration database user |
| `SPRING_FLYWAY_PASSWORD` | Yes | *(Flyway Password)* | Dedicated Flyway migration database password |
| `APP_JWT_SECRET` | Yes | *(64-character secret)* | HS512/HS256 signing secret for authentication tokens |
| `APP_ADMIN_KEY` | Yes | *(Platform Key)* | Platform administration secret |
| `APP_SUPERADMIN_BOOTSTRAP_ENABLED` | Yes | `false` | Disabled by default in production |
| `APP_WEBPUSH_VAPID_PUBLIC_KEY` | Optional | *(Base64 VAPID Key)* | VAPID public key for Web Push notifications |
| `APP_WEBPUSH_VAPID_PRIVATE_KEY` | Optional | *(Base64 VAPID Key)* | VAPID private key for Web Push notifications |
| `APP_WEBPUSH_VAPID_SUBJECT` | Optional | `mailto:support@queueless.in` | VAPID contact subject |

---

## 5. Step-by-Step Initial Deployment

### Method A: Automated Setup via Render Blueprint (`render.yaml`)

1. Log in to [Render Dashboard](https://dashboard.render.com).
2. Click **New +** -> **Blueprint**.
3. Connect your GitHub repository (`QUEUELESS`).
4. Select the `master` branch. Render will detect `render.yaml` automatically.
5. In the blueprint review screen:
   - Provide values for all `sync: false` secrets listed in Section 4.
6. Click **Apply**.
7. Render will create the Web Service `queueless-backend` and begin building the Docker image.

### Method B: Manual Web Service Creation in Render Dashboard

1. Log in to [Render Dashboard](https://dashboard.render.com).
2. Click **New +** -> **Web Service**.
3. Connect your GitHub repository (`QUEUELESS`).
4. Configure service settings:
   - **Name:** `queueless-backend`
   - **Region:** `Singapore (Southeast Asia)`
   - **Branch:** `master`
   - **Root Directory:** Leave empty (root)
   - **Runtime:** `Docker`
   - **Dockerfile Path:** `./Dockerfile`
   - **Instance Type:** `Free` ($0/mo)
5. Expand **Advanced Settings**:
   - **Auto-Deploy:** Set to `No` (manual release promotion).
   - **Health Check Path:** `/actuator/health`
6. Under **Environment Variables**, add each variable specified in the mapping table in Section 4.
7. Click **Create Web Service**.

---

## 6. GitHub Actions Automated Deployment Setup

The repository includes a dedicated deployment workflow: `.github/workflows/render-deploy.yml`.

### 6.1 Configuring Deploy Hook in GitHub Secrets

1. In the Render Dashboard, open your service `queueless-backend`.
2. Go to **Settings** -> Scroll down to **Deploy Hook**.
3. Click **Add Deploy Hook** (or copy the existing URL):
   `https://api.render.com/deploy/srv-xxxxxxxxxxxx?key=yyyyyyyyyyyy`
4. In your GitHub repository:
   - Go to **Settings** -> **Secrets and variables** -> **Actions**.
   - Click **New repository secret**.
   - **Name:** `RENDER_DEPLOY_HOOK_URL`
   - **Value:** Paste the Render Deploy Hook URL.
   - Click **Add secret**.

### 6.2 Triggering a Deployment via GitHub Actions

1. In GitHub, go to the **Actions** tab.
2. Select **QueueLess — Render Free Backend Deployment** from the left sidebar.
3. Click **Run workflow**.
4. (Optional) Provide a deployment justification or release note.
5. Click the green **Run workflow** button.
6. The workflow will validate the secret and trigger the webhook. Render will pull the latest commit from `master` and deploy the Docker container.

---

## 7. Cloudflare Worker Frontend Integration

QueueLess uses Cloudflare Workers to serve the React frontend and proxy backend API calls.

### Updating `BACKEND_API_URL`
Once the Render service is deployed, it receives an onrender domain, e.g.:
`https://queueless-backend.onrender.com`

Update the Cloudflare Worker environment:
1. In Cloudflare Dashboard -> **Workers & Pages** -> select the `queueless` worker.
2. Go to **Settings** -> **Variables and Secrets**.
3. Update `BACKEND_API_URL`:
   ```
   BACKEND_API_URL = "https://queueless-backend.onrender.com"
   ```
4. Deploy the update.

**Zero frontend code modification:** The frontend client continues calling `/api/*` relative to its own origin. Cloudflare Worker proxies all `/api/*` requests directly to Render, eliminating CORS preflight overhead and mixed content issues.

---

## 8. Post-Deployment Verification & Smoke Tests

After Render completes the container build and reports the service as **Live**, perform the following verification steps:

### Step 1: Health Endpoint Probe
```bash
curl -I https://queueless-backend.onrender.com/actuator/health
```
**Expected Output:** `HTTP/1.1 200 OK` with JSON `{"status":"UP"}`.

### Step 2: Proxied API Probe through Cloudflare Worker
```bash
curl -I https://queueless.theabhisheksunny.workers.dev/api/health
```
**Expected Output:** `HTTP/1.1 200 OK`.

### Step 3: Check Container Memory & Startup Logs
In Render Dashboard -> **Logs**, confirm:
- `Starting QueueLessApplication using Java 21...`
- `The following 1 profile is active: "prod"`
- `Executing Pre-Migration Database Isolation Check: ExpectedEnv=PRODUCTION`
- `Pre-migration database isolation verified successfully.`
- `Flyway: Successfully validated X migrations`
- `Started QueueLessApplication in X.XXX seconds`
- `QueueLess startup cleanup enabled: triggering asynchronous maintenance catch-up...`
- Memory usage in Metrics tab remains safely below 350 MB (cgroup limit 512 MB).

---

## 9. Cloud Run Fallback & Fast Rollback Procedure

All Cloud Run pipelines (`.github/workflows/cd.yml`), Docker configurations, and infrastructure code remain fully intact in the repository as an inactive fallback.

If Render Free encounters prolonged downtime, rate limits, or cold start latency unacceptable for a high-traffic event:

### 30-Second Fast Fallback:
1. Open Cloudflare Dashboard -> **Workers & Pages** -> `queueless`.
2. Edit `BACKEND_API_URL` under Variables:
   Change `https://queueless-backend.onrender.com` to the Cloud Run URL:
   `https://queueless-backend-staging-xxxxxxxx-el.a.run.app` (or production Cloud Run URL).
3. Save and deploy.
4. All frontend traffic instantly routes back to Cloud Run with **zero downtime** and **no git commits required**.
