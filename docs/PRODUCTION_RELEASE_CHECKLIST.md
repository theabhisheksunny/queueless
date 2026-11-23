# QueueLess — Production Release Checklist

This checklist must be reviewed and executed for every production deployment of **QueueLess**.

---

## 1. Pre-Release Verification (Before Approving GitHub Environment)

- [ ] **Clean CI Execution**:
  - [ ] All backend unit and security integration tests passed (`mvn clean test`).
  - [ ] Frontend TypeScript compilation and Vite build passed without errors (`npm run build`).
  - [ ] Full Playwright End-to-End browser test suite passed (`npx playwright test`).
  - [ ] Pre-flight policy script passed all 10 checks (`python scripts/verify_production_policies.py`).
  - [ ] Remediation verification script passed all 7 checks (`python scripts/verify_deployment_remediation.py`).
  - [ ] Container image Trivy vulnerability scan checked (no unresolved `CRITICAL` CVEs).

- [ ] **Artifact Verification & SLSA Attestation**:
  - [ ] Container image successfully built and tagged in staging repo: `asia-south1-docker.pkg.dev/${GCP_PROJECT_ID}/queueless-staging/backend:${COMMIT_SHA}`.
  - [ ] Authoritative SHA-256 digest resolved and cryptographically attested.
  - [ ] Release metadata manifest generated (`release-metadata.json`).

- [ ] **Staging Sign-Off**:
  - [ ] Staging Cloud Run service (`queueless-backend-staging`) successfully updated in `asia-south1`.
  - [ ] Staging Cloudflare Worker (`queueless-staging`) deployed.
  - [ ] Automated smoke test suite passed on staging (`python scripts/smoke_test.py https://queueless-staging.theabhisheksunny.workers.dev`).
  - [ ] No regression observed in core business workflows (QR generation, token issuance, PIN login).

- [ ] **Database Migration Review**:
  - [ ] Check if new Flyway migrations (`V...sql` or Java migrations) are introduced.
  - [ ] Confirm migrations are backwards-compatible (e.g. `ADD COLUMN IF NOT EXISTS`, nullable fields).
  - [ ] Confirm Flyway migration identity is dedicated (`queueless_migrator_prod`) and runtime role is unprivileged (`queueless_app_prod`).
  - [ ] Confirm canary record verification callback passes closed on environment mismatch.

---

## 2. Release Promotion Gate (Manual Approval)

- [ ] Navigate to GitHub Actions run for `QueueLess — Continuous Delivery & Multi-Environment Deployment`.
- [ ] Review pending deployment for environment **`production-promotion`**.
- [ ] Approve promotion to trigger `scripts/promote_image.py` (Crane deterministic byte-for-byte copy to `queueless-prod/backend`).
- [ ] Confirm source and destination SHA-256 digests match bit-for-bit without image rebuild.

---

## 3. Production Deployment (Manual Approval Gate)

- [ ] Inspect the `Deploy to Production` job waiting in `production` environment approval gate.
- [ ] Confirm the deployment target is `master` at the verified commit SHA and promoted image digest.
- [ ] Click **Approve and deploy**.

---

## 3. Post-Deployment Verification (Immediate Health Checks)

- [ ] **Automated Smoke Test Verification**:
  - [ ] Confirm the automated `Run Automated Post-Deployment Production Smoke Tests` step in GitHub Actions completed with code 0.
- [ ] **Manual Edge Probe**:
  - [ ] Open `https://queueless.theabhisheksunny.workers.dev/` in an incognito window.
  - [ ] Confirm page loads within 1 second.
  - [ ] Navigate to `/login` and confirm PIN login screen appears.
  - [ ] Probed `/actuator/health` returns `{"status":"UP"}`.
  - [ ] Probed `/api/public/queue/vapid-public-key` returns valid VAPID JSON.
- [ ] **Live Queue Smoke Check**:
  - [ ] Log in with a verified shop owner account.
  - [ ] Open shop dashboard.
  - [ ] Verify active queue status and token counter.
  - [ ] Test public customer QR link in mobile browser.

---

## 4. Post-Release Monitoring (First 60 Minutes)

- [ ] **Cloud Run Metrics** (GCP Console):
  - [ ] CPU and Memory utilization remain under 60%.
  - [ ] Instance count stabilizes at expected traffic level (0 to 3 instances).
  - [ ] Request latency 95th percentile under 300ms.
  - [ ] 5xx error rate is 0.0%.
- [ ] **Cloudflare Observability**:
  - [ ] Edge status codes remain 200/304 for assets and 200/401 for API.
  - [ ] No SSL/TLS handshake failures.
- [ ] **Database Pooler (Supabase)**:
  - [ ] Active connections remain well within pooler limit (<= 15 connections).
