# QueueLess — Production Rollback & Disaster Recovery Runbook

This runbook provides emergency rollback and disaster recovery procedures for **QueueLess**.

---

## 1. Google Cloud Run Backend Rollback

Google Cloud Run stores every deployed container revision immutably. If a newly deployed backend revision experiences errors or failing health checks, you can instantly route 100% of user traffic back to the previous healthy revision with **zero downtime**.

### Step 1: List Recent Revisions
```bash
gcloud run revisions list \
  --service=queueless-backend \
  --region=asia-south1 \
  --limit=5
```
Example output:
```
   REVISION                       ACTIVE  SERVICE            DEPLOYED BY                 DEPLOYED
✔  queueless-backend-00004-xyz    yes     queueless-backend  github-actions-deployer     2026-11-16 05:00:00 UTC
✔  queueless-backend-00003-rwf            queueless-backend  abhisheksunny307@gmail.com  2026-11-15 17:35:00 UTC
```

### Step 2: Instant 100% Traffic Rollback
Route all traffic back to the known healthy revision (e.g. `queueless-backend-00003-rwf`):
```bash
gcloud run services update-traffic queueless-backend \
  --region=asia-south1 \
  --to-revisions=queueless-backend-00003-rwf=100
```
*Traffic immediately shifts within 2 seconds. The broken revision stops receiving requests.*

### Step 3: Verify Backend Recovery
```bash
python scripts/smoke_test.py https://queueless.theabhisheksunny.workers.dev
```

---

## 2. Cloudflare Workers Frontend Rollback

Cloudflare Workers retains versioned deployments with instant rollback capability.

### Option A: Via Wrangler CLI
To roll back the production worker to the immediately preceding version:
```bash
npx wrangler rollback
```
Or rollback to a specific version ID:
```bash
# 1. List recent versions
npx wrangler versions list

# 2. Deploy specific healthy version
npx wrangler rollback <VERSION_ID>
```

### Option B: Via Cloudflare Dashboard
1. Log in to [Cloudflare Dashboard](https://dash.cloudflare.com/).
2. Navigate to **Workers & Pages $\rightarrow$ queueless $\rightarrow$ Deployments**.
3. Select the previous successful deployment and click **Rollback to this deployment**.

---

## 3. Database Safety & Migration Failure Handling

### Strict Working Rule
> [!CAUTION]
> **NEVER automatically drop or delete production database tables or columns.**
> Customer shop queues, active token sequences, and trial ledger records must remain intact.

### Handling Flyway Migration Failures
If a database migration (`V...__*.sql`) fails during deployment:

1. **Check Flyway Error in Cloud Run Logs**:
   ```bash
   gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=queueless-backend AND textPayload=~'FlywayException'" \
     --limit=20 \
     --format="value(textPayload)"
   ```

2. **Understand Migration State**:
   - Supabase PostgreSQL supports transactional DDL for most statements. If a migration fails mid-script, the transaction rolls back.
   - However, Flyway records a `success = false` row in the `flyway_schema_history` table.

3. **Repairing Flyway Schema History**:
   Once the underlying SQL error has been identified and fixed, repair the metadata table:
   ```bash
   mvn -f backend/pom.xml flyway:repair \
     -Dflyway.url="jdbc:postgresql://<supabase-pooler>:5432/postgres?sslmode=require" \
     -Dflyway.user="<db-user>" \
     -Dflyway.password="<db-password>"
   ```

4. **Backward-Compatibility Policy**:
   All QueueLess Flyway migrations are designed to be strictly backward-compatible:
   - Use `ADD COLUMN IF NOT EXISTS`.
   - Never remove columns or tables while previous revisions are still serving traffic.
   - Provide safe defaults for new columns.
   - Separate schema expansion from schema contraction by at least one release cycle.

---

## 4. Live Log Inspection & Incident Triage

### Cloud Run Live Logs
```bash
# Stream live application logs from production
gcloud run services logs tail queueless-backend --region=asia-south1
```

### Cloudflare Worker Live Logs
```bash
# Stream live HTTP request events from Cloudflare edge
npx wrangler tail
```

### Automated Smoke Diagnostic
Run the smoke test to isolate whether the failure is at the edge (Cloudflare) or the backend (Spring Boot):
```bash
python scripts/smoke_test.py https://queueless.theabhisheksunny.workers.dev
```
- If Step 1 or 2 fails $\rightarrow$ Cloudflare asset or edge route error.
- If Step 4, 5, or 6 fails $\rightarrow$ Cloud Run backend, container startup, or database connection error.
