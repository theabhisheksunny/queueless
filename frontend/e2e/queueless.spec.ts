import { test, expect } from '@playwright/test';

async function loginOwner(page: any) {
  const phone = '9811000001';
  const pin = '123456';
  await page.goto('/login');

  // Try routine login with mobile number + PIN
  await page.fill('input[type="tel"]', phone);
  await page.fill('input[name="pin"], input[type="password"]', pin);
  await page.locator('button[type="submit"]').click();

  try {
    await expect(page.getByText('Customer Queue Register')).toBeVisible({ timeout: 2500 });
  } catch {
    // If not registered yet, register directly
    await page.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();
    await page.fill('input[placeholder*="Royal Hair Salon"]', 'Test Shop');
    await page.selectOption('select', 'Barber Shop');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Test Owner');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', pin);
    await page.fill('input[name="confirmPin"]', pin);
    await page.locator('button[type="submit"]').click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();
  }
}

test.describe('QueueLess Digital Token Register E2E Tests', () => {

  test('1. Owner Demo Login & Dashboard Navigation', async ({ page }) => {
    await loginOwner(page);

    // Verify redirected to dashboard by checking dashboard header
    await expect(page.getByText('Customer Queue Register')).toBeVisible();
  });

  test('2. Start Queue, Add Customer Manually, Show QR Modal', async ({ page }) => {
    await loginOwner(page);
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
    await loginOwner(page);
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
    await loginOwner(page);
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    await page.goto('/settings');
    await expect(page.getByText('Shop Profile')).toBeVisible();
    await expect(page.locator('input[type="number"]')).toBeVisible();
  });

  test('6. Full Customer QR Scan, Token Issuance and Tracking Journey', async ({ page, context }) => {
    // 1. Owner starts queue and gets public queue link
    await loginOwner(page);
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
    await loginOwner(page);
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Reset to clean queue session for deterministic token lifecycle
    const closeBtn = page.getByRole('button', { name: /Close Queue/i });
    if (await closeBtn.isVisible()) {
      await closeBtn.click();
      await page.getByRole('button', { name: /Close Queue/i }).last().click();
    }
    const startQueueBtn = page.getByRole('button', { name: /Start Queue/i });
    await expect(startQueueBtn).toBeVisible();
    await startQueueBtn.click();
    await expect(page.getByText('OPEN')).toBeVisible();

    // Verify initial Currently Serving shows Waiting for next customer
    const servingCard = page.locator('[data-testid="currently-serving-card"]');
    await expect(servingCard).toBeVisible();
    await expect(servingCard.getByTestId('serving-name')).toContainText(/No customer serving|Waiting for next customer|प्रतीक्षा/i);

    // Add Customer A
    const custA = 'Customer A ' + Math.floor(Math.random() * 1000);
    await page.getByRole('button', { name: /Add Customer/i }).click();
    await page.fill('input[placeholder*="Rahul Sharma"]', custA);
    await page.getByRole('button', { name: /Generate Token/i }).click();

    // Add Customer B
    const custB = 'Customer B ' + Math.floor(Math.random() * 1000);
    await page.getByRole('button', { name: /Add Customer/i }).click();
    await page.fill('input[placeholder*="Rahul Sharma"]', custB);
    await page.getByRole('button', { name: /Generate Token/i }).click();

    // Add Customer C
    const custC = 'Customer C ' + Math.floor(Math.random() * 1000);
    await page.getByRole('button', { name: /Add Customer/i }).click();
    await page.fill('input[placeholder*="Rahul Sharma"]', custC);
    await page.getByRole('button', { name: /Generate Token/i }).click();

    // Locate customer rows
    const rowA = page.locator('[data-testid="customer-row"]', { hasText: custA });
    const rowB = page.locator('[data-testid="customer-row"]', { hasText: custB });
    const rowC = page.locator('[data-testid="customer-row"]', { hasText: custC });

    await expect(rowA).toBeVisible();
    await expect(rowB).toBeVisible();
    await expect(rowC).toBeVisible();

    // --- TEST A: Start Serving Customer A ---
    await rowA.getByRole('button', { name: /Start Serving/i }).click();

    // Verify row badge changes to SERVING
    await expect(rowA.getByText('SERVING')).toBeVisible();

    // Verify Currently Serving Card updates
    await expect(servingCard.getByTestId('serving-name')).toContainText(custA);
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();

    // --- TEST B: Complete Serving Customer A ---
    await rowA.getByRole('button', { name: /Complete/i }).click();

    // Verify Customer A row badge in Completed / History
    await page.getByRole('button', { name: /Completed \/ History|पूर्ण/i }).click();
    await expect(rowA.getByText('COMPLETED')).toBeVisible();
    await page.getByRole('button', { name: /All Active|सभी सक्रिय/i }).click();

    // Verify rowA action buttons are not visible in active list
    await expect(rowA).not.toBeVisible();

    // Verify Currently Serving card reverts to empty state
    await expect(servingCard.getByTestId('serving-name')).toContainText(/No customer serving|Waiting for next customer|प्रतीक्षा/i);

    // --- TEST C: Call Next Customer (Should automatically pick Customer B) ---
    const nextBtn = page.getByRole('button', { name: /Call Next Customer/i });
    await expect(nextBtn).toBeVisible();
    await nextBtn.click();

    // Verify Customer B is now CALLED
    await expect(rowB.getByText('CALLED')).toBeVisible();

    // Now start serving Customer B
    await rowB.getByRole('button', { name: /Start Serving/i }).click();

    // Verify Customer B is now SERVING
    await expect(rowB.getByText('SERVING')).toBeVisible();
    await expect(servingCard.getByTestId('serving-name')).toContainText(custB);
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();
  });

  test('8. Regression Suite: Test D - Multiple Serving Customers simultaneously', async ({ page }) => {
    await loginOwner(page);
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
    await loginOwner(page);
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    const startQueueBtn = page.getByRole('button', { name: /Start Queue/i });
    if (await startQueueBtn.isVisible()) {
      await startQueueBtn.click();
    }

    // Get QR join link
    await page.getByRole('button', { name: /Show QR Code/i }).first().click();
    const joinLinkHref = await page.getByTitle(/Open Customer View/i).getAttribute('href');
    await page.locator('button:has(svg.lucide-x)').first().click();

    // 2. Open Customer page in a second window
    const customerPage = await context.newPage();
    await customerPage.goto(joinLinkHref!);
    const custName = 'SSE Customer ' + Math.floor(Math.random() * 1000);
    await customerPage.fill('input[placeholder*="Rahul Sharma"]', custName);
    await customerPage.getByRole('button', { name: /Get My Token/i }).click();
    await expect(customerPage.getByText('Your Token Number')).toBeVisible();

    // --- TEST E: Real-Time SSE Event Delivery ---
    // Owner marks Customer as Serving
    const customerRow = page.locator('[data-testid="customer-row"]', { hasText: custName });
    await customerRow.getByRole('button', { name: /Start Serving/i }).click();

    // Customer page MUST update to serving alert via live SSE event without reload
    await expect(customerPage.getByText(/It's Your Turn!|You are being served|आपकी बारी|आपकी सेवा/i)).toBeVisible({ timeout: 5000 });

    // --- TEST F: Page Refresh Rehydration ---
    // Refresh Customer Page
    await customerPage.reload();
    // After reload, customer should still see serving state from current DB state
    await expect(customerPage.getByText(/It's Your Turn!|You are being served|आपकी बारी|आपकी सेवा/i)).toBeVisible();

    // Refresh Owner Dashboard
    await page.reload();
    // After reload, currently serving card should still contain the customer
    await expect(page.locator('[data-testid="currently-serving-card"]')).toContainText(custName);

    // --- TEST G: SSE Reconnection on Network Failure Simulation ---
    // Simulate temporary offline / reconnect on Customer page
    await customerPage.context().setOffline(true);
    // Wait briefly
    await customerPage.waitForTimeout(1000);
    // Bring back online
    await customerPage.context().setOffline(false);

    // Complete the customer on Owner Dashboard
    const freshRow = page.locator('[data-testid="customer-row"]', { hasText: custName });
    await freshRow.getByRole('button', { name: /Complete/i }).click();

    // Customer page reconnected SSE stream should receive the COMPLETED event
    await expect(customerPage.getByText(/COMPLETED|service has been completed|सेवा पूर्ण/i).first()).toBeVisible({ timeout: 10000 });

    await customerPage.close();
  });
});
