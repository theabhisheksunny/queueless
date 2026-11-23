# QueueLess — CI/CD Pipeline Setup & Configuration Guide

This guide describes how to configure GitHub Actions, GitHub Environments, Google Cloud Platform (GCP), and Cloudflare Workers to activate the automated pipeline for **QueueLess**.

---

## 1. GitHub Repository Configuration

### 1.1. Create GitHub Environments

Navigate to **GitHub Repository $\rightarrow$ Settings $\rightarrow$ Environments**:

#### 1. Environment: `staging`
- **Environment Name**: `staging`
- **Deployment Branches**: Selected branches $\rightarrow$ `master`
- **Reviewers**: None required (executes automatically during staging CD workflow run).
- **Associated Job**: `deploy-staging` in `.github/workflows/cd.yml`.

#### 2. Environment: `production-promotion`
- **Environment Name**: `production-promotion`
- **Purpose**: Acts as the mandatory administrative approval and cryptographic gate governing OCI container image promotion from the Staging Artifact Registry to the Production Artifact Registry.
- **Associated Job**: `promote-image` in `.github/workflows/cd.yml`.
- **Required Reviewers**: Enable the **Required reviewers** checkbox and assign authorized release managers (e.g., repository owners).
- **Promotion Approval Gate**:
  - Halts the CD pipeline immediately after staging deployment and automated staging smoke tests succeed.
  - Upon release manager review and manual approval, executes `scripts/promote_image.py` using Crane OCI copy to transfer the exact verified staging image byte-for-byte to `queueless-prod/backend`.
  - Verifies cryptographic SLSA build provenance and asserts byte-for-byte SHA-256 digest equality.
  - Production deployment cannot be scheduled or executed without this promotion gate succeeding.
- **Deployment Branches**: Selected branches $\rightarrow$ `master` only.

#### 3. Environment: `production`
- **Environment Name**: `production`
- **Purpose**: Protects live customer-facing Google Cloud Run backend and Cloudflare Workers frontend environments.
- **Associated Job**: `deploy-production` in `.github/workflows/cd.yml`.
- **Required Reviewers**: Enable the **Required reviewers** checkbox and assign authorized release managers.
- **Deployment Approval Gate**:
  - Enforces final human review before deploying the promoted immutable image (`@sha256:...`) and frontend production bundle to live production services.
- **Wait timer**: Optional (e.g., 0 minutes).
- **Deployment Branches**: Selected branches $\rightarrow$ `master` only.

---

### 1.2. Configure GitHub Secrets & Variables

Navigate to **Settings $\rightarrow$ Secrets and variables $\rightarrow$ Actions**.

#### Repository Secrets (Required)

| Secret Name | Description | Example / Target Format |
| :--- | :--- | :--- |
| `GCP_PROJECT_ID` | GCP Project ID | `queueless-prod-514814` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full resource name of the Workload Identity Provider | `projects/1043223278240/locations/global/workloadIdentityPools/github-actions-pool/providers/github-provider` |
| `GCP_STAGING_DEPLOYER_SA` | Dedicated Staging Deployer Service Account email | `queueless-staging-deployer@queueless-prod-514814.iam.gserviceaccount.com` |
| `GCP_PROMOTER_SA` | Dedicated Release Promoter Service Account email | `queueless-promoter@queueless-prod-514814.iam.gserviceaccount.com` |
| `GCP_PROD_DEPLOYER_SA` | Dedicated Production Deployer Service Account email | `queueless-prod-deployer@queueless-prod-514814.iam.gserviceaccount.com` |
| `CLOUDFLARE_API_TOKEN` | Cloudflare API Token for Wrangler deployment | Token with `Worker Scripts:Edit`, `Account:Read` scopes |

#### Repository Variables (Required)

| Variable Name | Description | Example / Target Format |
| :--- | :--- | :--- |
| `QUEUELESS_STAGING_SUPABASE_PROJECT_REF` | Expected Supabase project ref for Staging isolation gate | `abcdefghijklmnopqrst` |
| `QUEUELESS_PROD_SUPABASE_PROJECT_REF` | Expected Supabase project ref for Production isolation gate | `zyxwvutsrqponmlkjihg` |

> [!TIP]
> **Zero Long-Lived Service Account Keys & Zero Shared Fallbacks**: QueueLess CI/CD uses **Google Cloud Workload Identity Federation (WIF)**. GitHub Actions requests an OIDC token (`id-token: write`) exchanged for short-lived Google Cloud credentials. Static JSON keys (`GCP_SA_KEY`) and monolithic shared deployer accounts (`GCP_SERVICE_ACCOUNT`) are eliminated. Each workflow stage uses an independent, least-privilege service account.

---

### 1.3. Branch Protection Rules

Navigate to **Settings $\rightarrow$ Branches $\rightarrow$ Add branch protection rule**:

- **Branch name pattern**: `master`
- Check **Require a pull request before merging** (Require at least 1 approval).
- Check **Require status checks to pass before merging**:
  - `Security & Production Policies Pre-Flight`
  - `Backend CI (Java 21 + Spring Boot + Security Tests)`
  - `Frontend CI (Node 20 + TypeScript + Oxlint + Vite Build)`
  - `Full-Stack E2E Integration Tests (Playwright)`
- Check **Require branches to be up to date before merging**.
- Check **Do not allow bypassing the above settings**.

---

## 2. Google Cloud Platform Setup

### 2.1. Enable Required GCP APIs
In GCP Cloud Shell or local terminal with `gcloud`:
```bash
gcloud services enable \
  artifactregistry.googleapis.com \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  iam.googleapis.com \
  iamcredentials.googleapis.com \
  secretmanager.googleapis.com
```

### 2.2. Create Isolated Artifact Registry Repositories
Create separate staging and production container repositories in the Mumbai region (`asia-south1`):
```bash
# Staging Artifact Registry repository
gcloud artifacts repositories create queueless-staging \
  --repository-format=docker \
  --location=asia-south1 \
  --description="QueueLess Staging Container Repository"

# Production Artifact Registry repository
gcloud artifacts repositories create queueless-prod \
  --repository-format=docker \
  --location=asia-south1 \
  --description="QueueLess Production Container Repository"
```

### 2.3. Create Least-Privilege CI/CD Service Accounts
QueueLess uses distinct identities for staging deployment, image promotion, and production deployment:

```bash
# 1. Create Staging Deployer Service Account
gcloud iam service-accounts create queueless-staging-deployer \
  --display-name="QueueLess Staging CI/CD Deployer"

# Grant Cloud Run developer role (can deploy revisions, cannot alter IAM policies)
gcloud projects add-iam-policy-binding queueless-prod-514814 \
  --member="serviceAccount:queueless-staging-deployer@queueless-prod-514814.iam.gserviceaccount.com" \
  --role="roles/run.developer"

# Grant Artifact Registry writer on staging repository only
gcloud artifacts repositories add-iam-policy-binding queueless-staging \
  --location=asia-south1 \
  --member="serviceAccount:queueless-staging-deployer@queueless-prod-514814.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"

# 2. Create Release Promoter Service Account
gcloud iam service-accounts create queueless-promoter \
  --display-name="QueueLess Release Promoter"

# Grant read access to staging repository and write access to production repository
gcloud artifacts repositories add-iam-policy-binding queueless-staging \
  --location=asia-south1 \
  --member="serviceAccount:queueless-promoter@queueless-prod-514814.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.reader"

gcloud artifacts repositories add-iam-policy-binding queueless-prod \
  --location=asia-south1 \
  --member="serviceAccount:queueless-promoter@queueless-prod-514814.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"

# 3. Create Production Deployer Service Account
gcloud iam service-accounts create queueless-prod-deployer \
  --display-name="QueueLess Production CI/CD Deployer"

# Grant Cloud Run developer role
gcloud projects add-iam-policy-binding queueless-prod-514814 \
  --member="serviceAccount:queueless-prod-deployer@queueless-prod-514814.iam.gserviceaccount.com" \
  --role="roles/run.developer"
```

### 2.4. Create Dedicated Cloud Run Runtime Service Accounts
Cloud Run containers run under dedicated runtime identities rather than the default compute service account:

```bash
# 1. Staging Runtime Service Account
gcloud iam service-accounts create queueless-staging-sa \
  --display-name="QueueLess Staging Cloud Run Runtime SA"

# Allow Staging Deployer to act as the Staging Runtime SA
gcloud iam service-accounts add-iam-policy-binding \
  queueless-staging-sa@queueless-prod-514814.iam.gserviceaccount.com \
  --role="roles/iam.serviceAccountUser" \
  --member="serviceAccount:queueless-staging-deployer@queueless-prod-514814.iam.gserviceaccount.com"

# 2. Production Runtime Service Account
gcloud iam service-accounts create queueless-prod-sa \
  --display-name="QueueLess Production Cloud Run Runtime SA"

# Allow Production Deployer to act as the Production Runtime SA
gcloud iam service-accounts add-iam-policy-binding \
  queueless-prod-sa@queueless-prod-514814.iam.gserviceaccount.com \
  --role="roles/iam.serviceAccountUser" \
  --member="serviceAccount:queueless-prod-deployer@queueless-prod-514814.iam.gserviceaccount.com"
```

### 2.5. Configure Workload Identity Federation (WIF)
Eliminate static service account keys by establishing trust between GitHub Actions and GCP:
```bash
# 1. Create a Workload Identity Pool
gcloud iam workload-identity-pools create "github-actions-pool" \
  --project="queueless-prod-514814" \
  --location="global" \
  --display-name="GitHub Actions Pool"

# 2. Create a Workload Identity Provider for GitHub OIDC
gcloud iam workload-identity-pools providers create-oidc "github-provider" \
  --project="queueless-prod-514814" \
  --location="global" \
  --workload-identity-pool="github-actions-pool" \
  --display-name="GitHub Actions Provider" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository == 'theabhisheksunny/queueless'"

# 3. Allow GitHub repository to impersonate the CI/CD service accounts
REPO_PRINCIPAL="principalSet://iam.googleapis.com/projects/$(gcloud projects describe queueless-prod-514814 --format='value(projectNumber)')/locations/global/workloadIdentityPools/github-actions-pool/attribute.repository/theabhisheksunny/queueless"

gcloud iam service-accounts add-iam-policy-binding \
  queueless-staging-deployer@queueless-prod-514814.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="$REPO_PRINCIPAL"

gcloud iam service-accounts add-iam-policy-binding \
  queueless-promoter@queueless-prod-514814.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="$REPO_PRINCIPAL"

gcloud iam service-accounts add-iam-policy-binding \
  queueless-prod-deployer@queueless-prod-514814.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="$REPO_PRINCIPAL"

# 4. Get the full provider resource name for GitHub Secrets:
gcloud iam workload-identity-pools providers describe "github-provider" \
  --project="queueless-prod-514814" \
  --location="global" \
  --workload-identity-pool="github-actions-pool" \
  --format="value(name)"
```
*Add the output of step 4 to the `GCP_WORKLOAD_IDENTITY_PROVIDER` GitHub Secret.*

### 2.6. Create Secrets in Google Secret Manager
Cloud Run mounts database passwords, Flyway migration credentials, and JWT keys securely at runtime via `--set-secrets`:

```bash
# --- Staging Secrets ---
echo -n "jdbc:postgresql://<staging-pooler>:5432/postgres?sslmode=require" | \
  gcloud secrets create QUEUELESS_STAGING_DB_URL --data-file=-
echo -n "staging-jwt-secret-min-32-chars-long" | \
  gcloud secrets create QUEUELESS_STAGING_JWT_SECRET --data-file=-
echo -n "staging-admin-passphrase" | \
  gcloud secrets create QUEUELESS_STAGING_ADMIN_KEY --data-file=-
echo -n "staging-superadmin-password" | \
  gcloud secrets create QUEUELESS_STAGING_SUPERADMIN_PASS --data-file=-
echo -n "queueless_migrator_staging.<staging-project-ref>" | \
  gcloud secrets create QUEUELESS_STAGING_MIGRATOR_USER --data-file=-
echo -n "strong-staging-migrator-password" | \
  gcloud secrets create QUEUELESS_STAGING_MIGRATOR_PASS --data-file=-

# Grant Secret Accessor to Staging Runtime SA
for SEC in QUEUELESS_STAGING_DB_URL QUEUELESS_STAGING_JWT_SECRET QUEUELESS_STAGING_ADMIN_KEY QUEUELESS_STAGING_SUPERADMIN_PASS QUEUELESS_STAGING_MIGRATOR_USER QUEUELESS_STAGING_MIGRATOR_PASS; do
  gcloud secrets add-iam-policy-binding "$SEC" \
    --member="serviceAccount:queueless-staging-sa@queueless-prod-514814.iam.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
done

# --- Production Secrets ---
echo -n "jdbc:postgresql://<prod-pooler>:5432/postgres?sslmode=require" | \
  gcloud secrets create QUEUELESS_PROD_DB_URL --data-file=-
echo -n "prod-jwt-secret-min-32-chars-high-entropy" | \
  gcloud secrets create QUEUELESS_PROD_JWT_SECRET --data-file=-
echo -n "prod-admin-passphrase-high-entropy" | \
  gcloud secrets create QUEUELESS_PROD_ADMIN_KEY --data-file=-
echo -n "queueless_migrator_prod.<prod-project-ref>" | \
  gcloud secrets create QUEUELESS_PROD_MIGRATOR_USER --data-file=-
echo -n "strong-production-migrator-password" | \
  gcloud secrets create QUEUELESS_PROD_MIGRATOR_PASS --data-file=-

# Grant Secret Accessor to Production Runtime SA
for SEC in QUEUELESS_PROD_DB_URL QUEUELESS_PROD_JWT_SECRET QUEUELESS_PROD_ADMIN_KEY QUEUELESS_PROD_MIGRATOR_USER QUEUELESS_PROD_MIGRATOR_PASS; do
  gcloud secrets add-iam-policy-binding "$SEC" \
    --member="serviceAccount:queueless-prod-sa@queueless-prod-514814.iam.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
done
```

### 2.7. One-Time Public Invoker IAM Bootstrap
To maintain least privilege, routine deployment workflows do not include `--allow-unauthenticated` (which would require dangerous `roles/run.admin` or `setIamPolicy` permissions on deployer service accounts). Public ingress access is granted once during service provisioning:

```bash
# Allow unauthenticated ingress to Staging Cloud Run service
gcloud run services add-iam-policy-binding queueless-backend-staging \
  --region=asia-south1 \
  --member="allUsers" \
  --role="roles/run.invoker"

# Allow unauthenticated ingress to Production Cloud Run service
gcloud run services add-iam-policy-binding queueless-backend \
  --region=asia-south1 \
  --member="allUsers" \
  --role="roles/run.invoker"
```

---

## 3. Cloudflare Workers Setup

1. In the [Cloudflare Dashboard](https://dash.cloudflare.com/), navigate to **My Profile $\rightarrow$ API Tokens $\rightarrow$ Create Token**.
2. Select **Edit Cloudflare Workers** template.
3. Confirm Account and Zone resources.
4. Copy the generated token into the `CLOUDFLARE_API_TOKEN` GitHub secret.

---

## 4. Local CLI Testing of Workflows & Scripts

Before triggering remote runs, all scripts can be verified locally:

```bash
# 1. Test policy and security pre-flight checks
python scripts/verify_production_policies.py

# 2. Run post-deployment smoke tests against local or production server
python scripts/smoke_test.py http://localhost:8080
python scripts/smoke_test.py https://queueless.theabhisheksunny.workers.dev
```
