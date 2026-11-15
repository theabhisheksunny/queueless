import { test, expect } from '@playwright/test';

test.describe('QueueLess Real-World Stability Audit & Workflow Verification', () => {

  test('Complete 17-Step Real-World Operational Workflow & Security Audit', async ({ browser, request }) => {
    // Collect console errors to verify clean runtime execution
    const consoleErrors: string[] = [];

    // =========================================================================
    // WORKFLOW 1: Owner Registration and Login
    // =========================================================================
    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();
    ownerPage.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(`[Owner Console Error] ${msg.text()}`);
    });
    ownerPage.on('pageerror', err => {
      consoleErrors.push(`[Owner Page Error] ${err.message}`);
    });

    await ownerPage.goto('/login');
    await expect(ownerPage.locator('h1')).toContainText('QueueLess');

    // Switch to Register tab
    await ownerPage.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    const uniqueId = Date.now();
    const ownerEmail = `audit_owner_${uniqueId}@queueless.com`;
    const shopName = `Dr. Mehta Dental Clinic ${uniqueId % 1000}`;

    await ownerPage.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await ownerPage.selectOption('select', 'Clinic & Doctor');
    await ownerPage.fill('input[placeholder*="Ramesh Sharma"]', 'Dr. Rajesh Mehta');
    const uniquePhone = '98' + (uniqueId % 100000000).toString().padStart(8, '0');
    await ownerPage.fill('input[placeholder*="9876543210"]', uniquePhone);
    await ownerPage.getByRole('button', { name: /Send OTP/i }).click();
    await ownerPage.fill('input[name="pin"]', '123456');
    await ownerPage.fill('input[name="confirmPin"]', '123456');

    // Submit registration
    await ownerPage.locator('button[type="submit"]').click();

    // Verify redirected to Owner Dashboard with new shop profile
    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();
    await expect(ownerPage.getByText(shopName).first()).toBeVisible();
    await expect(ownerPage.getByText('Clinic & Doctor').first()).toBeVisible();

    // =========================================================================
    // WORKFLOW 2: Start Today's Queue
    // =========================================================================
    const startQueueBtn = ownerPage.getByRole('button', { name: /Start Queue/i });
    await expect(startQueueBtn).toBeVisible();
    await startQueueBtn.click();

    // Verify status switches to OPEN
    await expect(ownerPage.getByText('OPEN')).toBeVisible();

    // Verify initial clean counters
    const servingCard = ownerPage.locator('[data-testid="currently-serving-card"]');
    await expect(servingCard.getByTestId('serving-token')).toHaveText('-');
    await expect(servingCard.getByTestId('serving-name')).toContainText(/No customer serving|कोई ग्राहक नहीं/i);
    await expect(servingCard.getByTestId('serving-badge')).not.toBeVisible();

    // Verify initial stats: Total = 0, Waiting = 0
    await expect(ownerPage.getByText('Waiting Customers').locator('..').getByText('0')).toBeVisible();
    await expect(ownerPage.getByText('Total Issued Today').locator('..').getByText('0')).toBeVisible();

    // =========================================================================
    // WORKFLOW 3: Generate and Display QR Code
    // =========================================================================
    await ownerPage.getByRole('button', { name: /Show QR Code/i }).first().click();
    await expect(ownerPage.getByText('Scan to Join Queue')).toBeVisible();
    await expect(ownerPage.getByText(shopName).first()).toBeVisible();

    // Verify Download QR and Print Poster options
    await expect(ownerPage.getByRole('button', { name: /Download QR/i })).toBeVisible();
    await expect(ownerPage.getByRole('button', { name: /Print Counter Poster/i })).toBeVisible();

    // Extract public join URL from the modal link
    const joinLinkHref = await ownerPage.getByTitle(/Open Customer View/i).getAttribute('href');
    expect(joinLinkHref).toBeTruthy();
    expect(joinLinkHref).toMatch(/\/join\/QS-[A-Za-z0-9]+/);

    // Close QR modal
    await ownerPage.locator('button:has(svg.lucide-x)').first().click();
    await expect(ownerPage.getByText('Scan to Join Queue')).not.toBeVisible();

    // =========================================================================
    // WORKFLOW 4: Join Through QR Using Multiple Separate Customer Browser Sessions
    // =========================================================================

    // Customer Session A: Priya Sharma
    const customerContextA = await browser.newContext();
    const customerPageA = await customerContextA.newPage();
    customerPageA.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(`[CustomerA Console Error] ${msg.text()}`);
    });

    await customerPageA.goto(joinLinkHref!);
    await expect(customerPageA.getByText('Get Your Token')).toBeVisible();

    await customerPageA.fill('input[placeholder*="Rahul Sharma"]', 'Priya Sharma');
    await customerPageA.fill('input[placeholder*="9876543210"]', '9876511111');
    await customerPageA.getByRole('button', { name: /Get My Token/i }).click();

    // Verify Customer A receives Token 001
    await expect(customerPageA).toHaveURL(/.*\/track\/.*/);
    await expect(customerPageA.getByText(shopName).first()).toBeVisible();
    await expect(customerPageA.getByText('#001')).toBeVisible();
    await expect(customerPageA.getByText('WAITING', { exact: true })).toBeVisible();
    await expect(customerPageA.getByText('Ahead of You').locator('..').getByText('0')).toBeVisible();

    // Customer Session B: Vikram Patel
    const customerContextB = await browser.newContext();
    const customerPageB = await customerContextB.newPage();
    customerPageB.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(`[CustomerB Console Error] ${msg.text()}`);
    });

    await customerPageB.goto(joinLinkHref!);
    await expect(customerPageB.getByText('Get Your Token')).toBeVisible();

    await customerPageB.fill('input[placeholder*="Rahul Sharma"]', 'Vikram Patel');
    await customerPageB.fill('input[placeholder*="9876543210"]', '9876522222');
    await customerPageB.getByRole('button', { name: /Get My Token/i }).click();

    // Verify Customer B receives Token 002 with 1 person ahead
    await expect(customerPageB).toHaveURL(/.*\/track\/.*/);
    await expect(customerPageB.getByText('#002')).toBeVisible();
    await expect(customerPageB.getByText('WAITING', { exact: true })).toBeVisible();
    await expect(customerPageB.getByText('Ahead of You').locator('..').getByText('1')).toBeVisible();

    // =========================================================================
    // WORKFLOW 5: Add Customers Manually from Owner Dashboard
    // =========================================================================
    await ownerPage.getByRole('button', { name: /Add Customer/i }).click();
    await expect(ownerPage.getByText('Add Customer Manually')).toBeVisible();

    await ownerPage.fill('input[placeholder*="Rahul Sharma"]', 'Suresh Raina');
    await ownerPage.fill('input[placeholder*="9876543210"]', '9876533333');
    await ownerPage.getByRole('button', { name: /Generate Token/i }).click();

    // =========================================================================
    // WORKFLOW 6: Verify Token Numbers and Strict FIFO Ordering
    // =========================================================================
    // Verify all 3 customers appeared in exact FIFO sequence
    const priyaRow = ownerPage.locator('[data-testid="customer-row"]', { hasText: 'Priya Sharma' });
    const vikramRow = ownerPage.locator('[data-testid="customer-row"]', { hasText: 'Vikram Patel' });
    const sureshRow = ownerPage.locator('[data-testid="customer-row"]', { hasText: 'Suresh Raina' });

    await expect(priyaRow).toBeVisible();
    await expect(priyaRow.getByText('#001')).toBeVisible();
    await expect(priyaRow.getByText('WAITING')).toBeVisible();
    await expect(priyaRow.getByText('• QR Joined')).toBeVisible();

    await expect(vikramRow).toBeVisible();
    await expect(vikramRow.getByText('#002')).toBeVisible();
    await expect(vikramRow.getByText('WAITING')).toBeVisible();
    await expect(vikramRow.getByText('• QR Joined')).toBeVisible();

    await expect(sureshRow).toBeVisible();
    await expect(sureshRow.getByText('#003')).toBeVisible();
    await expect(sureshRow.getByText('WAITING')).toBeVisible();
    await expect(sureshRow.getByText('• Manual Entry')).toBeVisible();

    // Verify updated dashboard counters
    await expect(ownerPage.getByText('Waiting Customers').locator('..').getByText('3')).toBeVisible();
    await expect(ownerPage.getByText('Total Issued Today').locator('..').getByText('3')).toBeVisible();
    // Verify currently serving remains strictly unassigned
    await expect(servingCard.getByTestId('serving-token')).toHaveText('-');
    await expect(servingCard.getByTestId('serving-badge')).not.toBeVisible();

    // =========================================================================
    // WORKFLOW 7: Call Next Customer (Priya Sharma #001)
    // =========================================================================
    await ownerPage.getByRole('button', { name: /Call Next Customer/i }).click();

    // Verify Priya becomes CALLED in owner dashboard
    await expect(priyaRow.getByText('CALLED')).toBeVisible();

    // CRITICAL: Ensure CALLED customer is NOT displayed as currently serving!
    await expect(servingCard.getByTestId('serving-token')).toHaveText('-');
    await expect(servingCard.getByTestId('serving-name')).toContainText(/No customer serving|कोई ग्राहक नहीं/i);
    await expect(servingCard.getByTestId('serving-badge')).not.toBeVisible();

    // WORKFLOW 14 Verification: Customer A tracking page receives live turn alert via SSE without refresh
    await expect(customerPageA.getByText("🔔 It's Your Turn!")).toBeVisible();
    await expect(customerPageA.getByText('CALLED', { exact: true })).toBeVisible();

    // =========================================================================
    // WORKFLOW 8: Start Serving Customer (Priya Sharma #001)
    // =========================================================================
    await priyaRow.getByRole('button', { name: /Start Serving/i }).click();

    // Verify Priya transitions to SERVING
    await expect(priyaRow.getByText('SERVING')).toBeVisible();

    // Verify Currently Serving card updates authoritatively
    await expect(servingCard.getByTestId('serving-token')).toHaveText('#001');
    await expect(servingCard.getByTestId('serving-name')).toContainText('Priya Sharma');
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();

    // Customer A tracking page updates via SSE to Serving status
    await expect(customerPageA.getByText('SERVING', { exact: true })).toBeVisible();
    await expect(customerPageA.getByText(/You are being served now/i)).toBeVisible();

    // =========================================================================
    // WORKFLOW 9: Complete Customer (Priya Sharma #001)
    // =========================================================================
    await priyaRow.getByRole('button', { name: /Complete/i }).click();

    // Verify Currently Serving card clears back to clean state
    await expect(servingCard.getByTestId('serving-token')).toHaveText('-');
    await expect(servingCard.getByTestId('serving-name')).toContainText(/No customer serving|कोई ग्राहक नहीं/i);
    await expect(servingCard.getByTestId('serving-badge')).not.toBeVisible();

    // Customer B tracking page updates via SSE: People Ahead drops from 1 to 0
    await expect(customerPageB.getByText('Ahead of You').locator('..').getByText('0')).toBeVisible();

    // =========================================================================
    // WORKFLOW 10: Skip and Recall Customer (Vikram Patel #002)
    // =========================================================================
    // Call Vikram
    await ownerPage.getByRole('button', { name: /Call Next Customer/i }).click();
    await expect(vikramRow.getByText('CALLED')).toBeVisible();

    // Owner clicks Skip
    await vikramRow.getByRole('button', { name: /Skip/i }).click();
    await expect(vikramRow.getByText('SKIPPED')).toBeVisible();

    // Owner clicks Re-queue / Recall
    await vikramRow.getByRole('button', { name: /Re-queue|Recall/i }).click();
    await expect(vikramRow.getByText('WAITING')).toBeVisible();

    // =========================================================================
    // WORKFLOW 11: Remove Customer (Suresh Raina #003)
    // =========================================================================
    await sureshRow.getByRole('button', { name: /Remove/i }).click();
    await expect(ownerPage.getByText('Remove Customer')).toBeVisible();
    await ownerPage.locator('.fixed button', { hasText: /^Remove$/i }).click();

    // Verify Suresh is removed from active queue
    await expect(sureshRow).not.toBeVisible();

    // =========================================================================
    // WORKFLOW 12: Customer Leaves Queue (Vikram Patel #002)
    // =========================================================================
    await customerPageB.getByRole('button', { name: /Leave Queue/i }).click();
    await expect(customerPageB.getByText('Are you sure you want to leave the queue?')).toBeVisible();
    await customerPageB.getByRole('button', { name: /Yes, Leave Queue/i }).click();

    // Customer tracking page displays left status and Rejoin option
    await expect(customerPageB.getByText('LEFT', { exact: true })).toBeVisible();
    await expect(customerPageB.getByRole('button', { name: /Rejoin Queue/i })).toBeVisible();

    // WORKFLOW 13 Verification: Owner Dashboard reflects Vikram's departure without refresh
    await expect(ownerPage.getByText('Waiting Customers').locator('..').getByText('0')).toBeVisible();

    // =========================================================================
    // WORKFLOW 15: Refresh and Reconnect Both Owner and Customer Pages
    // =========================================================================
    // Add one more customer to test state retention across refresh
    await ownerPage.getByRole('button', { name: /Add Customer/i }).click();
    await ownerPage.fill('input[placeholder*="Rahul Sharma"]', 'Persistent Guest');
    await ownerPage.getByRole('button', { name: /Generate Token/i }).click();

    const persistentRow = ownerPage.locator('[data-testid="customer-row"]', { hasText: 'Persistent Guest' });
    await persistentRow.getByRole('button', { name: /Start Serving/i }).click();
    await expect(servingCard.getByTestId('serving-name')).toContainText('Persistent Guest');
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();

    // Hard page reload on owner dashboard
    await ownerPage.reload();
    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();
    await expect(servingCard.getByTestId('serving-name')).toContainText('Persistent Guest');
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();

    // Simulate SSE disconnect & reconnect
    await ownerPage.context().setOffline(true);
    await ownerPage.waitForTimeout(500);
    await ownerPage.context().setOffline(false);
    await ownerPage.waitForTimeout(1000);
    await expect(servingCard.getByTestId('serving-name')).toContainText('Persistent Guest');
    await expect(servingCard.getByTestId('serving-badge')).toBeVisible();

    // =========================================================================
    // WORKFLOW 16: Close the Queue
    // =========================================================================
    await ownerPage.getByRole('button', { name: /Close Queue/i }).first().click();
    await ownerPage.locator('.fixed button', { hasText: /^Close Queue$/i }).click();

    // Verify queue status switches to CLOSED
    await expect(ownerPage.getByText('CLOSED')).toBeVisible();
    await expect(ownerPage.getByRole('button', { name: /Start Queue/i })).toBeVisible();

    // =========================================================================
    // WORKFLOW 17: Verify Closed Queues Reject New Customer Joins
    // =========================================================================
    const customerContextC = await browser.newContext();
    const customerPageC = await customerContextC.newPage();

    await customerPageC.goto(joinLinkHref!);
    await expect(customerPageC.getByText('Get Your Token')).toBeVisible();

    // Attempt to join the closed queue
    await customerPageC.fill('input[placeholder*="Rahul Sharma"]', 'Late Applicant');
    await customerPageC.getByRole('button', { name: /Get My Token/i }).click();

    // Verify application rejects join with explicit closed queue notice
    await expect(customerPageC.getByText(/Queue is currently closed/i)).toBeVisible();

    // =========================================================================
    // SECURITY & AUTHORIZATION AUDIT CHECKS
    // =========================================================================
    // 1. Unauthenticated request to /api/queue/dashboard must be rejected (401/403)
    const unauthDash = await request.get('/api/queue/dashboard');
    expect([401, 403]).toContain(unauthDash.status());

    // 2. Unauthenticated customer status mutation must be rejected (401/403)
    const unauthPatch = await request.patch('/api/queue/customers/1/status?status=SERVING');
    expect([401, 403]).toContain(unauthPatch.status());

    // 3. Invalid public tracking token must return 400 or 404
    const invalidTrack = await request.get('/api/public/queue/track/non-existent-uuid-token-999');
    expect([400, 404]).toContain(invalidTrack.status());

    // 4. Verify 0 console errors occurred during the entire multi-session audit
    const severeErrors = consoleErrors.filter(e => !e.includes('favicon'));
    expect(severeErrors).toHaveLength(0);

    // Clean up all contexts
    await customerPageA.close();
    await customerPageB.close();
    await customerPageC.close();
    await customerContextA.close();
    await customerContextB.close();
    await customerContextC.close();
    await ownerPage.close();
    await ownerContext.close();
  });
});
