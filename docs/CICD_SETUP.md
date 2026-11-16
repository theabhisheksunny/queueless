# QueueLess — CI/CD Pipeline Setup & Configuration Guide

This guide describes how to configure GitHub Actions, GitHub Environments, Google Cloud Platform (GCP), and Cloudflare Workers to activate the automated pipeline for **QueueLess**.

---

## 1. GitHub Repository Configuration

### 1.1. Create GitHub Environments

Navigate to **GitHub Repository $\rightarrow$ Settings $\rightarrow$ Environments**:

#### 1. Environment: `staging`
- **Environment Name**: `staging`
- **Deployment Branches**: Selected branches $\rightarrow$ `master`
- **Reviewers**: None required (automatically deploys on merge to `master`).

#### 2. Environment: `production`
- **Environment Name**: `production`
- **Required Reviewers**: Enable **Required reviewers** checkbox and add authorized release managers (e.g., repository owners).
- **Wait timer**: Optional (e.g., 0 minutes).
- **Deployment Branches**: Selected branches $\rightarrow$ `master` only.

---

### 1.2. Configure GitHub Secrets & Variables

Navigate to **Settings $\rightarrow$ Secrets and variables $\rightarrow$ Actions**.

#### Repository Secrets & Variables (Required)

| Secret Name | Description | Example / Target Format |
| :--- | :--- | :--- |
| `GCP_PROJECT_ID` | GCP Project ID | `queueless-prod-514814` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full resource name of the Workload Identity Provider | `projects/1043223278240/locations/global/workloadIdentityPools/github-actions-pool/providers/github-provider` |
| `GCP_SERVICE_ACCOUNT` | GCP Service Account email for GitHub Actions | `github-actions-deployer@queueless-prod-514814.iam.gserviceaccount.com` |
| `CLOUDFLARE_API_TOKEN` | Cloudflare API Token | Token with `Worker Scripts:Edit`, `Account:Read` scopes |

> [!TIP]
> **Zero Long-Lived Service Account Keys**: QueueLess CI/CD uses **Google Cloud Workload Identity Federation (WIF)**. GitHub Actions requests an OIDC token (`id-token: write`) exchanged for short-lived Google Cloud credentials. Static JSON keys (`GCP_SA_KEY`) are eliminated. Sensitive database URLs, JWT keys, and admin keys are stored and retrieved securely directly from **Google Secret Manager**.

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

### 2.2. Create Google Artifact Registry Repository
Create the container repository in the Mumbai region (`asia-south1`):
```bash
gcloud artifacts repositories create queueless \
  --repository-format=docker \
  --location=asia-south1 \
  --description="QueueLess Docker Container Repository"
```

### 2.3. Create Service Account for GitHub Actions
```bash
# 1. Create service account
gcloud iam service-accounts create github-actions-deployer \
  --display-name="GitHub Actions CI/CD Deployer"

# 2. Grant Cloud Run Admin role
gcloud projects add-iam-policy-binding queueless-prod-514814 \
  --member="serviceAccount:github-actions-deployer@queueless-prod-514814.iam.gserviceaccount.com" \
  --role="roles/run.admin"

# 3. Grant Artifact Registry Writer role
gcloud projects add-iam-policy-binding queueless-prod-514814 \
  --member="serviceAccount:github-actions-deployer@queueless-prod-514814.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"

# 4. Grant Service Account User role (allows Cloud Run to run as the runtime SA)
gcloud projects add-iam-policy-binding queueless-prod-514814 \
  --member="serviceAccount:github-actions-deployer@queueless-prod-514814.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountUser"
```

### 2.4. Configure Workload Identity Federation (WIF)
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

# 3. Allow GitHub repository to impersonate the service account
gcloud iam service-accounts add-iam-policy-binding \
  github-actions-deployer@queueless-prod-514814.iam.gserviceaccount.com \
  --project="queueless-prod-514814" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/$(gcloud projects describe queueless-prod-514814 --format='value(projectNumber)')/locations/global/workloadIdentityPools/github-actions-pool/attribute.repository/theabhisheksunny/queueless"

# 4. Get the full provider resource name for GitHub Secrets:
gcloud iam workload-identity-pools providers describe "github-provider" \
  --project="queueless-prod-514814" \
  --location="global" \
  --workload-identity-pool="github-actions-pool" \
  --format="value(name)"
```
*Add the output of step 4 to the `GCP_WORKLOAD_IDENTITY_PROVIDER` GitHub Secret.*

### 2.5. Create Secrets in Google Secret Manager
Cloud Run mounts database passwords and JWT secrets securely at runtime via `--set-secrets`:
```bash
# Staging Secrets
echo -n "jdbc:postgresql://<staging-pooler>:5432/postgres?sslmode=require" | \
  gcloud secrets create QUEUELESS_STAGING_DB_URL --data-file=-
echo -n "staging-jwt-secret-min-32-chars-long" | \
  gcloud secrets create QUEUELESS_STAGING_JWT_SECRET --data-file=-
echo -n "staging-admin-passphrase" | \
  gcloud secrets create QUEUELESS_STAGING_ADMIN_KEY --data-file=-

# Production Secrets
echo -n "jdbc:postgresql://<prod-pooler>:5432/postgres?sslmode=require" | \
  gcloud secrets create QUEUELESS_PROD_DB_URL --data-file=-
echo -n "prod-jwt-secret-min-32-chars-high-entropy" | \
  gcloud secrets create QUEUELESS_PROD_JWT_SECRET --data-file=-
echo -n "prod-admin-passphrase-high-entropy" | \
  gcloud secrets create QUEUELESS_PROD_ADMIN_KEY --data-file=-

# Grant Secret Manager Secret Accessor to the Cloud Run runtime service account
PROJECT_NUM=$(gcloud projects describe queueless-prod-514814 --format='value(projectNumber)')
gcloud projects add-iam-policy-binding queueless-prod-514814 \
  --member="serviceAccount:${PROJECT_NUM}-compute@developer.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
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
