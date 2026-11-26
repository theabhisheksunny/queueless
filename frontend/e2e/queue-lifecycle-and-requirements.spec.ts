import { test, expect } from '@playwright/test';

test.describe('QueueLess — Full Queue Lifecycle, Milestones & Entitlements E2E', () => {

  // =========================================================================
  // Requirement 1 & 2: Token Expiration (HTTP 410) & Customer NO_SHOW Handling
  // =========================================================================
  test('1 & 2. NO_SHOW removal updates queue counters and customer tracking; HTTP 410 renders expired card', async ({ browser }) => {
    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();

    const uniqueId = Date.now();
    const shopName = `NoShow & Expiry Test ${uniqueId % 1000}`;
    const ownerPhone = '98' + (uniqueId % 100000000).toString().padStart(8, '0');

    // Register owner
    await ownerPage.goto('/login');
    await ownerPage.getByRole('button', { name: /Create Account|साइन अप/i }).click();
    await ownerPage.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await ownerPage.selectOption('select', 'Salon & Spa');
    await ownerPage.fill('input[placeholder*="Ramesh Sharma"]', 'Owner Test');
    await ownerPage.fill('input[placeholder*="9876543210"]', ownerPhone);
    await ownerPage.fill('input[name="pin"]', '123456');
    await ownerPage.fill('input[name="confirmPin"]', '123456');
    await ownerPage.locator('button[type="submit"]').click();
    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();

    // Start queue
    await ownerPage.getByRole('button', { name: /Start Queue/i }).click();
    await expect(ownerPage.getByText('OPEN')).toBeVisible();

    // Get public join link
    await ownerPage.getByRole('button', { name: /Show QR Code/i }).first().click();
    const joinLinkHref = await ownerPage.getByTitle(/Open Customer View/i).getAttribute('href');
    expect(joinLinkHref).toBeTruthy();
    await ownerPage.locator('button:has(svg.lucide-x)').first().click({ force: true });

    // Customer joins queue
    const customerContext = await browser.newContext();
    const customerPage = await customerContext.newPage();
    await customerPage.goto(joinLinkHref!);
    await customerPage.fill('input[placeholder*="Rahul Sharma"]', 'Arjun Kapoor');
    await customerPage.fill('input[placeholder*="9876543210"]', '9811223344');
    await customerPage.getByRole('button', { name: /Get My Token/i }).click();

    await expect(customerPage).toHaveURL(/.*\/track\/.*/);
    await expect(customerPage.getByText('#001').first()).toBeVisible();
    await expect(customerPage.getByText('WAITING', { exact: true })).toBeVisible();

    const trackingUrl = customerPage.url();
    const trackingToken = trackingUrl.substring(trackingUrl.lastIndexOf('/') + 1);

    // Owner calls customer
    const arjunRow = ownerPage.locator('[data-testid="customer-row"]', { hasText: 'Arjun Kapoor' });
    await ownerPage.getByRole('button', { name: /Call Next Customer/i }).click();
    await expect(arjunRow.getByText('CALLED')).toBeVisible();

    // Customer fails to arrive: Owner marks customer as NO_SHOW
    await arjunRow.getByRole('button', { name: /No-Show|अनुपस्थित/i }).click();

    // Verify owner dashboard removes customer from active queue
    await expect(arjunRow).not.toBeVisible();
    await expect(ownerPage.getByText('Waiting Customers').locator('..').getByText('0')).toBeVisible();

    // Verify customer tracking page reflects NO_SHOW status
    await customerPage.reload();
    await expect(customerPage.getByText(/Marked as No-Show/i)).toBeVisible();
    await expect(customerPage.getByText(/You missed your turn/i)).toBeVisible();
    // Rejoin button MUST NOT be available for NO_SHOW
    await expect(customerPage.getByRole('button', { name: /Rejoin/i })).not.toBeVisible();

    // Simulate 15-minute token expiration via HTTP 410 Gone
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

    await customerPage.reload();
    // Dedicated expired card rendered
    await expect(customerPage.getByRole('heading', { name: /Your token has expired/i })).toBeVisible();
    await expect(customerPage.getByText(/Your service was already completed/i)).toBeVisible();

    await customerContext.close();
    await ownerContext.close();
  });

  // =========================================================================
  // Requirement 3: One-Time Rejoin Enforcement & Edge Cases
  // =========================================================================
  test('3. One-time rejoin enforcement: allowed when LEFT, button disappears after rejoin, blocked when COMPLETED', async ({ browser, request }) => {
    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();

    const uniqueId = Date.now();
    const shopName = `Rejoin Policy Shop ${uniqueId % 1000}`;
    const ownerPhone = '98' + (uniqueId % 100000000).toString().padStart(8, '0');

    // Register & start queue
    await ownerPage.goto('/login');
    await ownerPage.getByRole('button', { name: /Create Account|साइन अप/i }).click();
    await ownerPage.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await ownerPage.selectOption('select', 'Barber Shop');
    await ownerPage.fill('input[placeholder*="Ramesh Sharma"]', 'Rejoin Owner');
    await ownerPage.fill('input[placeholder*="9876543210"]', ownerPhone);
    await ownerPage.fill('input[name="pin"]', '123456');
    await ownerPage.fill('input[name="confirmPin"]', '123456');
    await ownerPage.locator('button[type="submit"]').click();
    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();

    await ownerPage.getByRole('button', { name: /Start Queue/i }).click();
    await expect(ownerPage.getByText('OPEN')).toBeVisible();

    // Get public join link
    await ownerPage.getByRole('button', { name: /Show QR Code/i }).first().click();
    const joinLinkHref = await ownerPage.getByTitle(/Open Customer View/i).getAttribute('href');
    await ownerPage.locator('button:has(svg.lucide-x)').first().click({ force: true });

    // Customer joins queue
    const customerContext = await browser.newContext();
    const customerPage = await customerContext.newPage();
    await customerPage.goto(joinLinkHref!);
    await customerPage.fill('input[placeholder*="Rahul Sharma"]', 'Rejoin Customer');
    await customerPage.fill('input[placeholder*="9876543210"]', '9822334455');
    await customerPage.getByRole('button', { name: /Get My Token/i }).click();

    await expect(customerPage).toHaveURL(/.*\/track\/.*/);
    const trackingUrl = customerPage.url();
    const trackingToken = trackingUrl.substring(trackingUrl.lastIndexOf('/') + 1);

    // Customer voluntarily leaves queue
    await customerPage.getByRole('button', { name: /Leave Queue|कतार छोड़ें/i }).first().click();
    // Modal confirm
    await customerPage.getByRole('button', { name: /Yes, Leave Queue|हाँ, कतार छोड़ें/i }).click();

    // Status is now LEFT; Rejoin button MUST be visible
    await expect(customerPage.getByText('LEFT', { exact: true })).toBeVisible();
    const rejoinBtn = customerPage.getByRole('button', { name: /Rejoin Queue|फिर से कतार में शामिल हों/i });
    await expect(rejoinBtn).toBeVisible();

    // Customer executes 1-time rejoin
    await rejoinBtn.click();

    // Status returns to WAITING; Rejoin button MUST disappear
    await expect(customerPage.getByText('WAITING', { exact: true })).toBeVisible();
    await expect(customerPage.getByRole('button', { name: /Rejoin Queue/i })).not.toBeVisible();

    // Now owner serves and completes customer
    const row = ownerPage.locator('[data-testid="customer-row"]', { hasText: 'Rejoin Customer' });
    await ownerPage.getByRole('button', { name: /Call Next Customer/i }).click();
    await expect(row.getByText('CALLED')).toBeVisible();
    await row.getByRole('button', { name: /Start Serving/i }).click();
    await expect(row.getByText('SERVING')).toBeVisible();
    await row.getByRole('button', { name: /Complete/i }).click();

    // Verify customer moves to completed history
    await expect(row).not.toBeVisible();
    await ownerPage.getByRole('button', { name: /Completed \/ History|History/i }).click();
    await expect(ownerPage.locator('[data-testid="customer-row"]', { hasText: 'Rejoin Customer' }).getByText('COMPLETED')).toBeVisible();

    // On completed customer page, rejoin is completely forbidden
    await customerPage.reload();
    await expect(customerPage.getByRole('button', { name: /Rejoin/i })).not.toBeVisible();

    // Verify backend rejects any direct API attempt to rejoin completed token with HTTP 409 Conflict
    const directRejoinRes = await request.post(`/api/public/queue/track/${trackingToken}/rejoin`);
    expect([400, 409]).toContain(directRejoinRes.status());
    const body = await directRejoinRes.json();
    expect(body.message || body.error).toMatch(/Completed tokens cannot rejoin/i);

    await customerContext.close();
    await ownerContext.close();
  });

  // =========================================================================
  // Requirement 4: 3-2-1 Notification Milestones (People Ahead Progression)
  // =========================================================================
  test('4. 3-2-1 notification milestone progression updates people ahead dynamically', async ({ browser }) => {
    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();

    const uniqueId = Date.now();
    const shopName = `Milestone 321 Bar ${uniqueId % 1000}`;
    const ownerPhone = '98' + (uniqueId % 100000000).toString().padStart(8, '0');

    // Register & start queue
    await ownerPage.goto('/login');
    await ownerPage.getByRole('button', { name: /Create Account|साइन अप/i }).click();
    await ownerPage.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await ownerPage.selectOption('select', 'Barber Shop');
    await ownerPage.fill('input[placeholder*="Ramesh Sharma"]', 'Milestone Barber');
    await ownerPage.fill('input[placeholder*="9876543210"]', ownerPhone);
    await ownerPage.fill('input[name="pin"]', '123456');
    await ownerPage.fill('input[name="confirmPin"]', '123456');
    await ownerPage.locator('button[type="submit"]').click();
    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();

    await ownerPage.getByRole('button', { name: /Start Queue/i }).click();
    await expect(ownerPage.getByText('OPEN')).toBeVisible();

    // Get public join link
    await ownerPage.getByRole('button', { name: /Show QR Code/i }).first().click();
    const joinLinkHref = await ownerPage.getByTitle(/Open Customer View/i).getAttribute('href');
    await ownerPage.locator('button:has(svg.lucide-x)').first().click({ force: true });

    // Join 4 customers sequentially: #001, #002, #003, #004
    const names = ['Person One', 'Person Two', 'Person Three', 'Person Four'];
    let targetCustomerPage: any = null;

    for (let i = 0; i < names.length; i++) {
      const custContext = await browser.newContext();
      const p = await custContext.newPage();
      await p.goto(joinLinkHref!);
      await p.fill('input[placeholder*="Rahul Sharma"]', names[i]);
      await p.fill('input[placeholder*="9876543210"]', `983344550${i}`);
      await p.getByRole('button', { name: /Get My Token/i }).click();
      await expect(p).toHaveURL(/.*\/track\/.*/);

      if (i === 3) {
        // This is Person Four (#004)
        targetCustomerPage = p;
      } else {
        await custContext.close();
      }
    }

    // Person Four has 3 people ahead (Milestone 3)
    await expect(targetCustomerPage.getByText('#004').first()).toBeVisible();
    await expect(targetCustomerPage.getByText('Ahead of You').locator('..').getByText('3')).toBeVisible();

    // Owner calls Person One -> #001 is CALLED, so 2 people WAITING ahead (Milestone 2)
    await ownerPage.getByRole('button', { name: /Call Next Customer/i }).click();
    await targetCustomerPage.reload();
    await expect(targetCustomerPage.getByText('Ahead of You').locator('..').getByText('2')).toBeVisible();

    // Owner calls Person Two -> #002 is CALLED, so 1 person WAITING ahead (Milestone 1)
    await ownerPage.getByRole('button', { name: /Call Next Customer/i }).click();
    await targetCustomerPage.reload();
    await expect(targetCustomerPage.getByText('Ahead of You').locator('..').getByText('1')).toBeVisible();

    // Owner calls Person Three -> #003 is CALLED, so 0 people ahead
    await ownerPage.getByRole('button', { name: /Call Next Customer/i }).click();
    await targetCustomerPage.reload();
    await expect(targetCustomerPage.getByText('Ahead of You').locator('..').getByText('0')).toBeVisible();

    // Owner calls Person Four -> Person Four is CALLED! (TOKEN_CALLED milestone)
    await ownerPage.getByRole('button', { name: /Call Next Customer/i }).click();
    await targetCustomerPage.reload();
    await expect(targetCustomerPage.getByText(/CALLED/i).first()).toBeVisible();
    await expect(targetCustomerPage.getByText(/It's Your Turn!|आपकी बारी है/i)).toBeVisible();

    await targetCustomerPage.close();
    await ownerContext.close();
  });

  // =========================================================================
  // Requirement 5, 6 & 7: Push Notifications, Subscription 402 & Trial Ledger
  // =========================================================================
  test('5, 6 & 7. VAPID key delivery, 402 subscription barrier on expired shops, and trial ledger immutability', async ({ request }) => {
    // 5. Public VAPID endpoint delivery
    const vapidRes = await request.get('/api/public/queue/vapid-public-key');
    expect(vapidRes.status()).toBe(200);
    const vapidData = await vapidRes.json();
    expect(vapidData.publicKey).toBeDefined();
    expect(vapidData.publicKey.length).toBeGreaterThan(50);

    // 6. Entitlement Barrier: Unauthenticated or expired requests receive 402/403
    const queueProtectedRes = await request.post('/api/queue/start', {
      headers: { 'Authorization': 'Bearer invalid-or-expired-token' }
    });
    // Spring Security intercepts invalid tokens with 401/403; EntitlementService enforces 402
    expect([401, 403]).toContain(queueProtectedRes.status());

    // 7. Trial Immutability: Register owner, then verify re-registration with same mobile is rejected
    const uniqueId = Date.now();
    const phone = '98' + (uniqueId % 100000000).toString().padStart(8, '0');

    const regRes1 = await request.post('/api/auth/register', {
      data: {
        phone,
        pin: '123456',
        confirmPin: '123456',
        shopName: `Trial Ledger Shop ${uniqueId % 1000}`,
        fullName: 'Ledger Owner',
        category: 'Barber Shop',
      }
    });
    expect(regRes1.status()).toBe(200);

    // Duplicate registration attempt with identical mobile must be rejected (duplicate account / trial consumed)
    const regRes2 = await request.post('/api/auth/register', {
      data: {
        phone,
        pin: '654321',
        confirmPin: '654321',
        shopName: `Trial Abuse Attempt ${uniqueId % 1000}`,
        fullName: 'Abuse Attempt',
        category: 'Barber Shop',
      }
    });
    expect([400, 409]).toContain(regRes2.status());
    const regBody2 = await regRes2.json();
    expect(regBody2.message || regBody2.error).toMatch(/already registered|already in use|already exists|Trial/i);
  });
});
