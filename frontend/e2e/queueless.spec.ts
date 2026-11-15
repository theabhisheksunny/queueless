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
});
