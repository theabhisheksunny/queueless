import { test, expect } from '@playwright/test';

// Administrative credentials read strictly from Node process environment; never embedded in client code
const isStaging = (process.env.TEST_ENV === 'staging') || (process.env.PLAYWRIGHT_BASE_URL?.includes('workers.dev') ?? false);
const superAdminEmail = process.env.APP_SUPERADMIN_EMAIL || (isStaging ? 'superadmin-staging@queueless.in' : 'superadmin@queueless.in');
const superAdminPassword = process.env.APP_SUPERADMIN_PASSWORD || 'SuperAdminPass!2026';
const adminKey = process.env.APP_ADMIN_KEY || 'admin-secret-queueless-2026';
const hasAdminKey = Boolean(process.env.APP_ADMIN_KEY);
const hasSuperAdminPassword = Boolean(process.env.APP_SUPERADMIN_PASSWORD);

test.describe('QueueLess Super Admin Portal E2E Tests', () => {

  test('1. Super Admin authentication gate, dashboard summary cards, and navigation', async ({ page }) => {
    // 1. Visit Super Admin route
    await page.goto('/super-admin');

    // 2. Expect login prompt
    await expect(page.getByText('QueueLess Super Admin')).toBeVisible();
    await expect(page.getByText('Platform Payments & Subscription Management')).toBeVisible();
    await expect(page.getByPlaceholder(/Email or phone/i)).toBeVisible();
    await expect(page.getByPlaceholder(/Enter administrator password/i)).toBeVisible();

    // 3. Enter Super Admin Credentials
    await page.getByPlaceholder(/Email or phone/i).fill(superAdminEmail);
    await page.getByPlaceholder(/Enter administrator password/i).fill(superAdminPassword);
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

    if (isStaging && !hasSuperAdminPassword) {
      // Staging credentials protected; assert authentication gate functions correctly
      await expect(page.getByText(/Incorrect|Invalid|Bad credentials|Error|session has ended|Access restricted|Access denied/i)).toBeVisible();
      return;
    }

    // 4. Expect Dashboard view
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await expect(page.getByText('Shop Owners', { exact: true })).toBeVisible();
    await expect(page.getByText('Registered Branches', { exact: true })).toBeVisible();
    await expect(page.getByText('Active Subscriptions', { exact: true })).toBeVisible();
    await expect(page.getByText('Pending Payments', { exact: true })).toBeVisible();
    await expect(page.getByText('Approved Payments', { exact: true })).toBeVisible();
  });

  test('2. Payments screen: searchable and filterable table with details modal', async ({ page }) => {
    await page.goto('/super-admin');
    await page.getByPlaceholder(/Email or phone/i).fill(superAdminEmail);
    await page.getByPlaceholder(/Enter administrator password/i).fill(superAdminPassword);
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

    if (isStaging && !hasSuperAdminPassword) {
      await expect(page.getByText(/Incorrect|Invalid|Bad credentials|Error|session has ended|Access restricted|Access denied/i)).toBeVisible();
      return;
    }

    // Click on Payments in sidebar
    await page.getByRole('button', { name: /Payments/i }).first().click();
    await expect(page.getByRole('heading', { name: 'Payments' })).toBeVisible();

    // Verify filter buttons exist
    await expect(page.getByRole('button', { name: 'All' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Pending' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approved' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Rejected' })).toBeVisible();

    // Search bar is functional
    const searchInput = page.getByPlaceholder('Search owner, phone, or UTR...');
    await expect(searchInput).toBeVisible();
    await searchInput.fill('NONEXISTENT_UTR_QUERY_999');
    await expect(page.getByText('No payment transactions match the selected filters.')).toBeVisible();
    await searchInput.fill('');
  });

  test('3. Subscriptions screen: filter tabs and owner inspection modal', async ({ page }) => {
    await page.goto('/super-admin');
    await page.getByPlaceholder(/Email or phone/i).fill(superAdminEmail);
    await page.getByPlaceholder(/Enter administrator password/i).fill(superAdminPassword);
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

    if (isStaging && !hasSuperAdminPassword) {
      await expect(page.getByText(/Incorrect|Invalid|Bad credentials|Error|session has ended|Access restricted|Access denied/i)).toBeVisible();
      return;
    }

    // Click Subscriptions
    await page.getByRole('button', { name: /Subscriptions/i }).first().click();
    await expect(page.getByRole('heading', { name: 'Subscriptions', exact: true })).toBeVisible();

    // Verify filter tabs
    await expect(page.getByRole('button', { name: 'Active' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Expired' })).toBeVisible();
    await expect(page.getByPlaceholder('Search owner or shop...')).toBeVisible();
  });

  test('4. Users & Shops screen: merchant hierarchy search without customer PII', async ({ page }) => {
    await page.goto('/super-admin');
    await page.getByPlaceholder(/Email or phone/i).fill(superAdminEmail);
    await page.getByPlaceholder(/Enter administrator password/i).fill(superAdminPassword);
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

    if (isStaging && !hasSuperAdminPassword) {
      await expect(page.getByText(/Incorrect|Invalid|Bad credentials|Error|session has ended|Access restricted|Access denied/i)).toBeVisible();
      return;
    }

    // Click Users & Shops
    await page.getByRole('button', { name: /Users & Shops/i }).first().click();
    await expect(page.getByRole('heading', { name: /Users & Physical Shops/i })).toBeVisible();
    await expect(page.getByPlaceholder('Search by owner name, phone, email, or shop/branch name...')).toBeVisible();
  });

  test('5. Activity Logs screen and session logout', async ({ page }) => {
    await page.goto('/super-admin');
    await page.getByPlaceholder(/Email or phone/i).fill(superAdminEmail);
    await page.getByPlaceholder(/Enter administrator password/i).fill(superAdminPassword);
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

    if (isStaging && !hasSuperAdminPassword) {
      await expect(page.getByText(/Incorrect|Invalid|Bad credentials|Error|session has ended|Access restricted|Access denied/i)).toBeVisible();
      return;
    }

    // Click Activity Logs
    await page.getByRole('button', { name: /Activity Logs/i }).first().click();
    await expect(page.getByRole('heading', { name: /Platform Activity Logs/i })).toBeVisible();
    await expect(page.getByText('Last 50 Actions')).toBeVisible();

    // Click Exit Portal / Logout
    await page.getByRole('button', { name: /Exit Portal/i }).first().click();

    // Back to auth screen
    await expect(page.getByPlaceholder(/Enter administrator password/i)).toBeVisible();
  });

  test('6. Backend API enforces HTTP 403 for unauthorized requests and restricts X-Admin-Key to operational scope', async ({ request }) => {
    // 1. Unauthenticated request to /api/admin/dashboard/summary
    const unauthRes = await request.get('/api/admin/dashboard/summary');
    expect(unauthRes.status()).toBe(403);
    const unauthBody = await unauthRes.json();
    expect(unauthBody.error).toBe('Forbidden');

    // 2. Invalid X-Admin-Key on operational endpoint returns 403
    const badKeyRes = await request.get('/api/admin/config', {
      headers: { 'X-Admin-Key': 'completely-wrong-key' }
    });
    expect(badKeyRes.status()).toBe(403);

    // 3. Operational endpoint behavior with admin key
    if (isStaging && !hasAdminKey) {
      // On staging without remote APP_ADMIN_KEY secret, verify fallback key is strictly rejected with HTTP 403
      const fallbackKeyRes = await request.get('/api/admin/config', {
        headers: { 'X-Admin-Key': 'admin-secret-queueless-2026' }
      });
      expect(fallbackKeyRes.status()).toBe(403);
    } else {
      const validKeyRes = await request.get('/api/admin/config', {
        headers: { 'X-Admin-Key': adminKey }
      });
      expect(validKeyRes.status()).toBe(200);
      const data = await validKeyRes.json();
      expect(data.monthlyPrice).toBeDefined();

      // 4. Sensitive Super Admin endpoints strictly reject X-Admin-Key with HTTP 403 (SUPER_ADMIN role required)
      const keyOnDashboardRes = await request.get('/api/admin/dashboard/summary', {
        headers: { 'X-Admin-Key': adminKey }
      });
      expect(keyOnDashboardRes.status()).toBe(403);

      const keyOnPaymentsRes = await request.get('/api/admin/payments', {
        headers: { 'X-Admin-Key': adminKey }
      });
      expect(keyOnPaymentsRes.status()).toBe(403);

      const keyOnApproveRes = await request.post('/api/admin/payments/1/approve', {
        headers: { 'X-Admin-Key': adminKey }
      });
      expect(keyOnApproveRes.status()).toBe(403);
    }
  });

  test('7. Authenticated shop owner cannot access Super Admin portal (403 Access Denied)', async ({ page }) => {
    // Populate an authenticated shop owner session in localStorage before visiting the page
    await page.addInitScript(() => {
      localStorage.setItem('queueless_token', 'mock-owner-token');
      localStorage.setItem('queueless_branches', JSON.stringify([{ id: 1, name: 'Test Branch', category: 'SALON' }]));
      localStorage.setItem('queueless_businesses', JSON.stringify([{ id: 1, name: 'Test Business' }]));
      localStorage.setItem('queueless_active_shop_id', '1');
      localStorage.setItem('queueless_user', JSON.stringify({
        email: 'owner@test.com',
        phone: '9876543210',
        fullName: 'Test Owner',
        role: 'ROLE_OWNER',
      }));
    });

    await page.goto('/super-admin');

    // Owner role MUST be intercepted and denied access
    await expect(page.getByText('403 — Access Restricted')).toBeVisible();
    await expect(page.getByText(/Super Administrator Access Required|reserved exclusively for Platform Super Administrators/i).first()).toBeVisible();
    await expect(page.getByRole('button', { name: /Sign in with a different account|Go to Dashboard|Sign In as Super Administrator/i }).first()).toBeVisible();
  });
});
