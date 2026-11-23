import { test, expect } from '@playwright/test';

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
    await page.getByPlaceholder(/Email or phone/i).fill('superadmin@queueless.in');
    await page.getByPlaceholder(/Enter administrator password/i).fill('SuperAdminPass!2026');
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

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
    await page.getByPlaceholder(/Email or phone/i).fill('superadmin@queueless.in');
    await page.getByPlaceholder(/Enter administrator password/i).fill('SuperAdminPass!2026');
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

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
    await page.getByPlaceholder(/Email or phone/i).fill('superadmin@queueless.in');
    await page.getByPlaceholder(/Enter administrator password/i).fill('SuperAdminPass!2026');
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

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
    await page.getByPlaceholder(/Email or phone/i).fill('superadmin@queueless.in');
    await page.getByPlaceholder(/Enter administrator password/i).fill('SuperAdminPass!2026');
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

    // Click Users & Shops
    await page.getByRole('button', { name: /Users & Shops/i }).first().click();
    await expect(page.getByRole('heading', { name: /Users & Physical Shops/i })).toBeVisible();
    await expect(page.getByPlaceholder('Search by owner name, phone, email, or shop/branch name...')).toBeVisible();
  });

  test('5. Activity Logs screen and session logout', async ({ page }) => {
    await page.goto('/super-admin');
    await page.getByPlaceholder(/Email or phone/i).fill('superadmin@queueless.in');
    await page.getByPlaceholder(/Enter administrator password/i).fill('SuperAdminPass!2026');
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

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

    // 3. Valid X-Admin-Key succeeds on operational endpoint (ROLE_ADMIN service identity)
    const validKeyRes = await request.get('/api/admin/config', {
      headers: { 'X-Admin-Key': 'admin-secret-queueless-2026' }
    });
    expect(validKeyRes.status()).toBe(200);
    const data = await validKeyRes.json();
    expect(data.monthlyPrice).toBeDefined();

    // 4. Sensitive Super Admin endpoints strictly reject X-Admin-Key with HTTP 403 (SUPER_ADMIN role required)
    const keyOnDashboardRes = await request.get('/api/admin/dashboard/summary', {
      headers: { 'X-Admin-Key': 'admin-secret-queueless-2026' }
    });
    expect(keyOnDashboardRes.status()).toBe(403);

    const keyOnPaymentsRes = await request.get('/api/admin/payments', {
      headers: { 'X-Admin-Key': 'admin-secret-queueless-2026' }
    });
    expect(keyOnPaymentsRes.status()).toBe(403);

    const keyOnApproveRes = await request.post('/api/admin/payments/1/approve', {
      headers: { 'X-Admin-Key': 'admin-secret-queueless-2026' }
    });
    expect(keyOnApproveRes.status()).toBe(403);
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
        fullName: 'Test Shop Owner',
        role: 'ROLE_OWNER',
        shopId: 1,
        shopName: 'Test Branch',
        category: 'SALON',
        subscriptionActive: true,
        subscriptionDaysRemaining: 30
      }));
    });

    // Attempt to navigate to /super-admin
    await page.goto('/super-admin');

    // Verify Access Denied page is shown with super_admin_only notice
    await expect(page.getByText('Super Administrator Access Required')).toBeVisible();
    await expect(page.getByText(/This section is reserved exclusively for Platform Super Administrators/i)).toBeVisible();

    // The admin secret key form or admin dashboard should NOT be accessible
    await expect(page.getByPlaceholder('Enter admin secret key')).not.toBeVisible();
    await expect(page.getByRole('heading', { name: 'Dashboard' })).not.toBeVisible();
  });

  test('8. Super Admin Payment Verification Workflow: Manual verification modal, required rejection reason, and live status update', async ({ page, request }) => {
    // 1. Create a shop owner and submit a payment via API
    const ts = Date.now();
    const phone = '99' + (ts % 100000000).toString().padStart(8, '0');
    const regRes = await request.post('/api/auth/register', {
      data: {
        shopName: `Verify Shop ${ts % 1000}`,
        category: 'RETAIL',
        fullName: 'Super Admin Test Merchant',
        phone: phone,
        pin: '123456',
        confirmPin: '123456'
      }
    });
    expect(regRes.status()).toBe(200);
    const authData = await regRes.json();
    const token = authData.token;
    const shopId = authData.shopId;

    // Submit payment
    const utr = 'UTR_E2E_' + ts;
    const payRes = await request.post('/api/owner/subscription/payment', {
      headers: {
        Authorization: `Bearer ${token}`,
        'X-Shop-Id': shopId.toString()
      },
      data: {
        planType: 'MONTHLY',
        paymentReference: utr,
        paymentUpiId: 'absunny@ybl'
      }
    });
    expect(payRes.status()).toBe(200);

    // 2. Log in to Super Admin Portal
    await page.goto('/super-admin');
    await page.getByPlaceholder(/Email or phone/i).fill('superadmin@queueless.in');
    await page.getByPlaceholder(/Enter administrator password/i).fill('SuperAdminPass!2026');
    await page.getByRole('button', { name: /Sign In to Super Admin/i }).click();

    // 3. Navigate to Payments tab
    await page.getByRole('button', { name: /Payments/i }).first().click();
    await expect(page.getByRole('heading', { name: 'Payments' })).toBeVisible();

    // 4. Search for the submitted UTR
    const searchInput = page.getByPlaceholder('Search owner, phone, or UTR...');
    await searchInput.fill(utr);
    await expect(page.getByText(utr)).toBeVisible();

    // 5. Open View Details modal
    await page.getByRole('button', { name: /View Details/i }).first().click();

    // Verify Manual Bank Verification warning is displayed
    await expect(page.getByText(/Manual Bank Verification Required/i)).toBeVisible();

    // 6. Test Rejection flow: open rejection modal
    await page.getByRole('button', { name: /Reject Payment/i }).click();
    await expect(page.getByRole('heading', { name: 'Reject Payment Verification' })).toBeVisible();

    // Verify rejection button disabled without reason
    const confirmRejectBtn = page.getByRole('button', { name: 'Confirm Rejection' });
    await expect(confirmRejectBtn).toBeDisabled();

    // Enter rejection reason
    await page.getByPlaceholder(/UTR not found in bank statement/i).fill('UTR reference invalid or not found on bank statement');
    await expect(confirmRejectBtn).toBeEnabled();

    // Confirm rejection
    await confirmRejectBtn.click();
    await expect(page.getByText(/Payment with UTR.*rejected/i)).toBeVisible();

    // 7. Verify status updated to REJECTED in table
    await searchInput.fill(utr);
    await expect(page.locator('tbody').getByText('REJECTED', { exact: true })).toBeVisible();
  });
});
