import { test, expect } from '@playwright/test';

test.describe('Completed Token 15-Minute Expiration Policy E2E', () => {

  test('Customer completed token tracking lifecycle & expired UI verification', async ({ browser, request }) => {
    // 1. Register new owner & Start Queue
    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();

    await ownerPage.goto('/login');
    await ownerPage.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    const uniqueId = Date.now();
    const ownerEmail = `expiry_test_${uniqueId}@queueless.com`;
    const shopName = `Elite Styling Bar ${uniqueId % 1000}`;

    await ownerPage.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await ownerPage.selectOption('select', 'Salon & Spa');
    await ownerPage.fill('input[placeholder*="Ramesh Sharma"]', 'Vikram Malhotra');
    const uniquePhone = '98' + (uniqueId % 100000000).toString().padStart(8, '0');
    await ownerPage.fill('input[placeholder*="9876543210"]', uniquePhone);
    await ownerPage.fill('input[name="pin"]', '123456');
    await ownerPage.fill('input[name="confirmPin"]', '123456');
    await ownerPage.locator('button[type="submit"]').click();

    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();

    // Start Queue
    await ownerPage.getByRole('button', { name: /Start Queue/i }).click();
    await expect(ownerPage.getByText('OPEN')).toBeVisible();

    // Get public join link
    await ownerPage.getByRole('button', { name: /Show QR Code/i }).first().click();
    const joinLinkHref = await ownerPage.getByTitle(/Open Customer View/i).getAttribute('href');
    expect(joinLinkHref).toBeTruthy();
    await ownerPage.locator('button:has(svg.lucide-x)').first().click();

    // 2. Customer joins queue
    const customerContext = await browser.newContext();
    const customerPage = await customerContext.newPage();

    await customerPage.goto(joinLinkHref!);
    await customerPage.fill('input[placeholder*="Rahul Sharma"]', 'Sameer Sen');
    await customerPage.fill('input[placeholder*="9876543210"]', '9899001122');
    await customerPage.getByRole('button', { name: /Get My Token/i }).click();

    await expect(customerPage).toHaveURL(/.*\/track\/.*/);
    await expect(customerPage.getByText('#001').first()).toBeVisible();
    await expect(customerPage.getByText('WAITING', { exact: true })).toBeVisible();

    // Extract trackingToken from URL
    const trackingUrl = customerPage.url();
    const trackingToken = trackingUrl.substring(trackingUrl.lastIndexOf('/') + 1);

    // 3. Owner calls next, serves, and completes customer
    const sameerRow = ownerPage.locator('[data-testid="customer-row"]', { hasText: 'Sameer Sen' });
    await ownerPage.getByRole('button', { name: /Call Next Customer/i }).click();
    await expect(sameerRow.getByText('CALLED')).toBeVisible();

    await sameerRow.getByRole('button', { name: /Start Serving/i }).click();
    await expect(sameerRow.getByText('SERVING')).toBeVisible();

    await sameerRow.getByRole('button', { name: /Complete/i }).click();

    // 4. Before Expiration (Within 15 minutes):
    // Customer tracking page must display:
    // - Token number
    // - Shop name
    // - Completed status
    // - Message: "Your service has been completed. Thank you for visiting!"
    // - Zero rejoin action for completed tokens
    await expect(customerPage.getByText('#001').first()).toBeVisible();
    await expect(customerPage.getByText(shopName).first()).toBeVisible();
    await expect(customerPage.getByText('COMPLETED', { exact: true })).toBeVisible();
    await expect(customerPage.getByText(/Your service has been completed. Thank you for visiting!/i)).toBeVisible();
    await expect(customerPage.getByRole('button', { name: /Rejoin/i })).not.toBeVisible();

    // 5. Verify Owner can still view the completed customer in history
    await ownerPage.getByRole('button', { name: /Completed \/ History|History/i }).click();
    await expect(sameerRow).toBeVisible();
    await expect(sameerRow.getByText('COMPLETED')).toBeVisible();

    // Switch back to All Active tab for subsequent queue operations
    await ownerPage.getByRole('button', { name: /All Active|Active Queue/i }).click();

    // 6. Test Expiration Display via simulated expired state or HTTP 410
    // When tracking token returns 410 Gone, UI displays simple expired-token page
    await customerPage.route(`**/api/public/queue/track/${trackingToken}`, route => {
      route.fulfill({
        status: 410,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 410,
          error: 'Your token has expired. Your service was already completed.'
        })
      });
    });

    // Reload customer tracking page to simulate access after expiration
    await customerPage.reload();

    // Verify expired UI:
    // - "Your token has expired."
    // - "Your service was already completed. If you need another service, please ask the shopkeeper or scan the shop's QR code to get a new token."
    // - No customer details exposed
    await expect(customerPage.getByText('Your token has expired.')).toBeVisible();
    await expect(customerPage.getByText(/Your service was already completed. If you need another service/i)).toBeVisible();
    await expect(customerPage.getByText('#001')).not.toBeVisible();
    await expect(customerPage.getByText('Sameer Sen')).not.toBeVisible();

    // 7. Verify the same customer can join again through QR and receives a new token appended at the end
    const repeatCustomerContext = await browser.newContext();
    const repeatCustomerPage = await repeatCustomerContext.newPage();

    await repeatCustomerPage.goto(joinLinkHref!);
    await repeatCustomerPage.fill('input[placeholder*="Rahul Sharma"]', 'Sameer Sen');
    await repeatCustomerPage.fill('input[placeholder*="9876543210"]', '9899001122');
    await repeatCustomerPage.getByRole('button', { name: /Get My Token/i }).click();

    // Receives new token #002
    await expect(repeatCustomerPage).toHaveURL(/.*\/track\/.*/);
    await expect(repeatCustomerPage.getByText('#002').first()).toBeVisible();
    await expect(repeatCustomerPage.getByText('WAITING', { exact: true })).toBeVisible();

    // 8. Verify owner dashboard has both #001 (COMPLETED) and #002 (WAITING)
    await expect(ownerPage.getByText('Total Issued Today').locator('..').getByText('2')).toBeVisible();
    await expect(ownerPage.getByText('Waiting Customers').locator('..').getByText('1')).toBeVisible();

    await customerPage.close();
    await customerContext.close();
    await repeatCustomerPage.close();
    await repeatCustomerContext.close();
    await ownerPage.close();
    await ownerContext.close();
  });
});
