import { test, expect } from '@playwright/test';

test.describe('QueueLess Digital Token Register E2E Tests', () => {

  test('1. Owner Demo Login & Dashboard Navigation', async ({ page }) => {
    await page.goto('/login');

    // Check title and brand
    await expect(page.locator('h1')).toContainText('QueueLess');

    // Click 1-click Demo Login
    const demoBtn = page.getByRole('button', { name: /Demo Login/i });
    await expect(demoBtn).toBeVisible();
    await demoBtn.click();

    // Verify redirected to dashboard by checking dashboard header
    await expect(page.getByText('Customer Queue Register')).toBeVisible();
  });

  test('2. Start Queue, Add Customer Manually, Show QR Modal', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: /Demo Login/i }).click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Start Queue if closed
    const startQueueBtn = page.getByRole('button', { name: /Start Queue/i });
    if (await startQueueBtn.isVisible()) {
      await startQueueBtn.click();
      await expect(page.getByText('OPEN')).toBeVisible();
    }

    // Add unique Customer Manually
    const uniqueCustomer = 'Customer ' + Math.floor(Math.random() * 10000);
    await page.getByRole('button', { name: /Add Customer/i }).click();
    await expect(page.getByText('Add Customer Manually')).toBeVisible();

    await page.fill('input[placeholder*="Rahul Sharma"]', uniqueCustomer);
    await page.fill('input[placeholder*="9876543210"]', '9876500001');
    await page.getByRole('button', { name: /Generate Token/i }).click();

    // Verify modal closed and customer appeared in list with token number
    await expect(page.getByText(uniqueCustomer)).toBeVisible();

    // Open QR Code Modal
    await page.getByRole('button', { name: /Show QR Code/i }).first().click();
    await expect(page.getByText('Scan to Join Queue')).toBeVisible();
    await expect(page.getByRole('button', { name: /Download QR/i })).toBeVisible();

    // Close Modal
    await page.locator('button:has(svg.lucide-x)').first().click();
    await expect(page.getByText('Scan to Join Queue')).not.toBeVisible();
  });

  test('3. Bilingual Support: Toggle English and Hindi', async ({ page }) => {
    await page.goto('/login');

    // Click Language toggle
    const langBtn = page.getByRole('button', { name: /हिंदी|English/i });
    await expect(langBtn).toBeVisible();
    await langBtn.click();

    // Check Hindi text appeared on tab / button
    await expect(page.getByText('साइन इन करें')).toBeVisible();

    // Switch back to English
    await page.getByRole('button', { name: /English/i }).click();
    await expect(page.getByText('Sign In')).toBeVisible();
  });

  test('4. Subscription Page with UPI Payment Details', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: /Demo Login/i }).click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Navigate to Subscription page
    await page.goto('/subscription');
    await expect(page.getByText('Choose Your Subscription Plan')).toBeVisible();

    // Verify Monthly Plan and Yearly Plan
    await expect(page.getByText('Monthly Plan')).toBeVisible();
    await expect(page.getByText('Yearly Plan')).toBeVisible();

    // Verify UPI payment instructions and copy button
    await expect(page.getByRole('button', { name: /Copy UPI ID/i })).toBeVisible();
    await expect(page.getByText('Scan with GPay, PhonePe, Paytm, or BHIM')).toBeVisible();
  });

  test('5. Basic Shop Settings', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: /Demo Login/i }).click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    await page.goto('/settings');
    await expect(page.getByText('Shop Profile')).toBeVisible();
    await expect(page.locator('input[type="number"]')).toBeVisible();
  });

  test('6. Full Customer QR Scan, Token Issuance and Tracking Journey', async ({ page, context }) => {
    // 1. Owner starts queue and gets public queue link
    await page.goto('/login');
    await page.getByRole('button', { name: /Demo Login/i }).click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Open QR modal to get join link
    await page.getByRole('button', { name: /Show QR Code/i }).first().click();
    await expect(page.getByText('Scan to Join Queue')).toBeVisible();

    // 2. Open Customer Join Page in a second browser page
    const customerPage = await context.newPage();
    const joinLinkHref = await page.getByTitle(/Open Customer View/i).getAttribute('href');
    expect(joinLinkHref).toBeTruthy();

    await customerPage.goto(joinLinkHref!);
    await expect(customerPage.getByText('Get Your Token')).toBeVisible();

    // 3. Customer fills name and gets token
    const customerName = 'QR Guest ' + Math.floor(Math.random() * 1000);
    await customerPage.fill('input[placeholder*="Rahul Sharma"]', customerName);
    await customerPage.getByRole('button', { name: /Get My Token/i }).click();

    // 4. Customer is on tracking page
    await expect(customerPage).toHaveURL(/.*\/track\/.*/);
    await expect(customerPage.getByText('Your Token Number')).toBeVisible();
    await expect(customerPage.getByText('Ahead of You')).toBeVisible();
    await expect(customerPage.getByText('* Waiting time is an approximate estimate')).toBeVisible();

    // 5. Customer can leave queue
    await customerPage.getByRole('button', { name: /Leave Queue/i }).click();
    await customerPage.getByRole('button', { name: /Yes, Leave Queue/i }).click();
    await expect(customerPage.getByRole('button', { name: /Rejoin Queue/i })).toBeVisible();

    await customerPage.close();
  });

  test('7. Regression Suite: Test A, B, C - Basic Serving, Complete, Next Customer', async ({ page }) => {
    // Login to Dashboard
    await page.goto('/login');
    await page.getByRole('button', { name: /Demo Login/i }).click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Reset to clean queue session for deterministic token 001 lifecycle
    const closeBtn = page.getByRole('button', { name: /Close Queue/i }).first();
    if (await closeBtn.isVisible()) {
      await closeBtn.click();
      await page.locator('.fixed button', { hasText: /Close Queue/i }).click();
      await page.waitForTimeout(500);
    }
    const startQueueBtn = page.getByRole('button', { name: /Start Queue/i });
    if (await startQueueBtn.isVisible()) {
      await startQueueBtn.click();
      await expect(page.getByText('OPEN')).toBeVisible();
    }

    // Step A.1: Add Rahul (will be token 001)
    const rahulName = 'Rahul ' + Math.floor(Math.random() * 10000);
    await page.getByRole('button', { name: /Add Customer/i }).click();
    await page.fill('input[placeholder*="Rahul Sharma"]', rahulName);
    await page.getByRole('button', { name: /Generate Token/i }).click();
    await expect(page.getByText(rahulName)).toBeVisible();

    // Verify currently serving card is empty / none serving before call
    const servingCard = page.locator('[data-testid="currently-serving-card"]');
    await expect(servingCard.getByTestId('serving-token')).toHaveText('-');
    await expect(servingCard.getByTestId('serving-name')).toContainText(/No customer serving|कोई ग्राहक नहीं/i);
    await expect(servingCard.getByTestId('serving-badge')).not.toBeVisible();

    // Step A.2: Click Next Customer
    await page.getByRole('button', { name: /Call Next/i }).click();

    // Verify Rahul becomes CALLED in list
    const rahulRow = page.locator('[data-testid="customer-row"]', { hasText: rahulName });
    await expect(rahulRow.getByText('CALLED')).toBeVisible();

    // CRITICAL: Verify Rahul is NOT in Currently Serving merely because he is CALLED!
    await expect(servingCard.getByTestId('serving-token')).toHaveText('-');
    await expect(servingCard.getByTestId('serving-name')).toContainText(/No customer serving|कोई ग्राहक नहीं/i);
    await expect(servingCard.getByTestId('serving-badge')).not.toBeVisible();

    // Step A.3: Click Start Serving on Rahul
    await rahulRow.getByRole('button', { name: /Start Serving/i }).click();

    // Verify Rahul becomes SERVING
    await expect(rahulRow.getByText('SERVING')).toBeVisible();
    // Verify Currently Serving displays Rahul and token
    await expect(servingCard.getByTestId('serving-name')).toContainText(rahulName);
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();

    // Step B: Complete customer Rahul
    await rahulRow.getByRole('button', { name: /Complete/i }).click();

    // Verify Rahul disappears from Currently Serving
    await expect(servingCard.getByTestId('serving-token')).toHaveText('-');
    await expect(servingCard.getByTestId('serving-name')).toContainText(/No customer serving|कोई ग्राहक नहीं/i);
    await expect(servingCard.getByTestId('serving-badge')).not.toBeVisible();

    // Verify Rahul is in Completed / History tab
    await page.getByRole('button', { name: /Completed \/ History/i }).click();
    await expect(page.locator('[data-testid="customer-row"]', { hasText: rahulName }).getByText('COMPLETED')).toBeVisible();
    // Switch back to All Active
    await page.getByRole('button', { name: /All Active/i }).click();

    // Step C: Next customer Amit
    const amitName = 'Amit ' + Math.floor(Math.random() * 10000);
    await page.getByRole('button', { name: /Add Customer/i }).click();
    await page.fill('input[placeholder*="Rahul Sharma"]', amitName);
    await page.getByRole('button', { name: /Generate Token/i }).click();
    await expect(page.getByText(amitName)).toBeVisible();

    // Call Amit
    await page.getByRole('button', { name: /Call Next/i }).click();
    const amitRow = page.locator('[data-testid="customer-row"]', { hasText: amitName });
    await expect(amitRow.getByText('CALLED')).toBeVisible();
    await expect(servingCard.getByTestId('serving-token')).toHaveText('-');
    await expect(servingCard.getByTestId('serving-badge')).not.toBeVisible();

    // Start serving Amit
    await amitRow.getByRole('button', { name: /Start Serving/i }).click();
    await expect(amitRow.getByText('SERVING')).toBeVisible();
    await expect(servingCard.getByTestId('serving-name')).toContainText(amitName);
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();
  });

  test('8. Regression Suite: Test D - Multiple Serving Customers simultaneously', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: /Demo Login/i }).click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    const startQueueBtn = page.getByRole('button', { name: /Start Queue/i });
    if (await startQueueBtn.isVisible()) {
      await startQueueBtn.click();
    }

    const servingCard = page.locator('[data-testid="currently-serving-card"]');

    // Add Rahul
    const rahul = 'Rahul Multi ' + Math.floor(Math.random() * 1000);
    await page.getByRole('button', { name: /Add Customer/i }).click();
    await page.fill('input[placeholder*="Rahul Sharma"]', rahul);
    await page.getByRole('button', { name: /Generate Token/i }).click();

    // Add Amit
    const amit = 'Amit Multi ' + Math.floor(Math.random() * 1000);
    await page.getByRole('button', { name: /Add Customer/i }).click();
    await page.fill('input[placeholder*="Rahul Sharma"]', amit);
    await page.getByRole('button', { name: /Generate Token/i }).click();

    const rahulRow = page.locator('[data-testid="customer-row"]', { hasText: rahul });
    const amitRow = page.locator('[data-testid="customer-row"]', { hasText: amit });

    // Start serving Rahul
    await rahulRow.getByRole('button', { name: /Start Serving/i }).click();
    await expect(rahulRow.getByText('SERVING')).toBeVisible();
    await expect(servingCard.getByTestId('serving-name')).toContainText(rahul);

    // Start serving Amit simultaneously
    await amitRow.getByRole('button', { name: /Start Serving/i }).click();
    await expect(amitRow.getByText('SERVING')).toBeVisible();

    // Verify both are represented correctly
    await expect(servingCard.getByTestId('serving-name')).toContainText(rahul);
    await expect(servingCard.getByTestId('serving-name')).toContainText(amit);

    // Complete Rahul
    await rahulRow.getByRole('button', { name: /Complete/i }).click();

    // Verify Amit remains displayed as SERVING
    await expect(servingCard.getByTestId('serving-name')).toContainText(amit);
    await expect(servingCard.getByTestId('serving-name')).not.toContainText(rahul);
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();
  });

  test('9. Regression Suite: Test E, F, G - Real-Time SSE, Page Refresh, and SSE Reconnection', async ({ page, context }) => {
    // 1. Owner Login
    await page.goto('/login');
    await page.getByRole('button', { name: /Demo Login/i }).click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    const startQueueBtn = page.getByRole('button', { name: /Start Queue/i });
    if (await startQueueBtn.isVisible()) {
      await startQueueBtn.click();
    }

    // Get QR join link
    await page.getByRole('button', { name: /Show QR Code/i }).first().click();
    const joinLinkHref = await page.getByTitle(/Open Customer View/i).getAttribute('href');
    await page.locator('button:has(svg.lucide-x)').first().click();

    // 2. Customer joins in second page
    const customerPage = await context.newPage();
    await customerPage.goto(joinLinkHref!);
    const syncCustomer = 'Sync Guest ' + Math.floor(Math.random() * 1000);
    await customerPage.fill('input[placeholder*="Rahul Sharma"]', syncCustomer);
    await customerPage.getByRole('button', { name: /Get My Token/i }).click();
    await expect(customerPage.getByText('Ahead of You')).toBeVisible();

    // Test E: Owner starts serving customer -> Customer tracking page updates via SSE without manual refresh
    const syncRow = page.locator('[data-testid="customer-row"]', { hasText: syncCustomer });
    await syncRow.getByRole('button', { name: /Start Serving/i }).click();

    // Check Customer tracking page updated to SERVING via SSE
    await expect(customerPage.getByText(/You are being served now|SERVING/i)).toBeVisible();

    // Test F: Page refresh on Owner Dashboard retains serving state
    await page.reload({ waitUntil: 'commit' });
    await expect(page.getByText('Customer Queue Register')).toBeVisible();
    const servingCard = page.locator('[data-testid="currently-serving-card"]');
    await expect(servingCard.getByTestId('serving-name')).toContainText(syncCustomer);
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();

    // Test G: SSE Reconnection simulation (toggle offline / online or re-navigate)
    await page.context().setOffline(true);
    await page.waitForTimeout(500);
    await page.context().setOffline(false);
    await page.reload({ waitUntil: 'commit' });
    await expect(servingCard.getByTestId('serving-name')).toContainText(syncCustomer);
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();

    await customerPage.close();
  });
});
