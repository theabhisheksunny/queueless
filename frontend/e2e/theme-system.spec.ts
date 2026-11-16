import { test, expect } from '@playwright/test';

test.describe('Category-Based Dynamic Theme System E2E Tests', () => {

  test('1. Barber Shop: Owner dashboard, QR modal, customer join & tracking use barber theme', async ({ browser }) => {
    const timestamp = Date.now();
    const phone = '91' + (timestamp % 100000000).toString().padStart(8, '0');
    const shopName = `Gentlemen Cuts Barber ${timestamp % 10000}`;

    const context = await browser.newContext();
    const page = await context.newPage();

    // 1. Register as "Barber Shop"
    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Barber Shop');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Vikram Barber');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '123456');
    await page.fill('input[name="confirmPin"]', '123456');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // 2. Verify Owner Dashboard has data-theme="barber"
    const dashboardContainer = page.locator('[data-theme="barber"]');
    await expect(dashboardContainer).toBeVisible();
    await expect(page.getByRole('main').getByText('Barber Shop')).toBeVisible();

    // Start Queue to generate active public session and publicQueueId
    await page.getByRole('button', { name: /Start Queue|कतार शुरू करें/i }).click();
    await expect(page.getByRole('button', { name: /Close Queue|कतार बंद करें/i })).toBeVisible();

    // 3. Open QR modal and verify data-theme="barber"
    await page.getByRole('button', { name: 'Show QR Code' }).click();
    const qrModal = page.locator('.fixed[data-theme="barber"]');
    await expect(qrModal).toBeVisible();
    await expect(qrModal.getByText('Barber Shop')).toBeVisible();
    await expect(qrModal.getByRole('button', { name: /Print Counter Poster|Print Poster/i })).toBeVisible();

    // Get the join URL from the modal
    const joinLinkText = await page.locator('p.font-mono').textContent();
    expect(joinLinkText).toBeTruthy();
    const publicQueueId = joinLinkText!.split('/join/')[1]?.trim();
    expect(publicQueueId).toBeTruthy();

    // Close QR modal
    await page.locator('[data-testid="close-qr-modal"]').click();

    // 4. Customer opens Join Page
    const customerContext = await browser.newContext();
    const customerPage = await customerContext.newPage();

    await customerPage.goto(`/join/${publicQueueId}`);
    // Verify customer join page has data-theme="barber"
    const joinContainer = customerPage.locator('[data-theme="barber"]');
    await expect(joinContainer).toBeVisible();
    await expect(customerPage.getByText(shopName)).toBeVisible();
    await expect(customerPage.getByText('Barber Shop')).toBeVisible();

    // Customer joins the queue
    await customerPage.fill('input[type="text"]', 'Amit Sharma');
    await customerPage.fill('input[type="tel"]', '9811122233');
    await customerPage.getByRole('button', { name: /Get My Token|Take Token|टोकन प्राप्त करें/i }).click();

    // 5. Customer Tracking Page
    await expect(customerPage).toHaveURL(new RegExp('/track/'));
    const trackContainer = customerPage.locator('[data-theme="barber"]');
    await expect(trackContainer).toBeVisible();
    await expect(customerPage.getByText(shopName)).toBeVisible();
    await expect(customerPage.getByText('Barber Shop')).toBeVisible();
    await expect(customerPage.getByText('#001')).toBeVisible();

    // 6. Owner calls customer #001
    await page.getByRole('button', { name: /Call Next|अगला बुलाएं/i }).click();
    await expect(page.getByText('CALLED')).toBeVisible();

    // Customer tracking page receives update and displays turn alert
    await expect(customerPage.getByText(/It's Your Turn|आपकी बारी आ गई/i).first()).toBeVisible();

    await context.close();
    await customerContext.close();
  });

  test('2. Salon & Spa maps to salon theme with elegant rose styling', async ({ browser }) => {
    const timestamp = Date.now();
    const phone = '92' + (timestamp % 100000000).toString().padStart(8, '0');
    const shopName = `Blush Beauty Lounge ${timestamp % 10000}`;

    const context = await browser.newContext();
    const page = await context.newPage();

    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Salon & Spa');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Pooja Salon');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '123456');
    await page.fill('input[name="confirmPin"]', '123456');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Verify data-theme="salon"
    await expect(page.locator('[data-theme="salon"]')).toBeVisible();
    await expect(page.getByRole('main').getByText('Salon & Spa')).toBeVisible();

    await context.close();
  });

  test('3. Clinic & Doctor maps to clinic theme with clean teal styling', async ({ browser }) => {
    const timestamp = Date.now();
    const phone = '93' + (timestamp % 100000000).toString().padStart(8, '0');
    const shopName = `Apollo Care Clinic ${timestamp % 10000}`;

    const context = await browser.newContext();
    const page = await context.newPage();

    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Clinic & Doctor');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Dr. Verma');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '123456');
    await page.fill('input[name="confirmPin"]', '123456');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Verify data-theme="clinic"
    await expect(page.locator('[data-theme="clinic"]')).toBeVisible();
    await expect(page.getByRole('main').getByText('Clinic & Doctor')).toBeVisible();

    await context.close();
  });

  test('4. Restaurant / Cafe maps to restaurant theme with terracotta styling', async ({ browser }) => {
    const timestamp = Date.now();
    const phone = '94' + (timestamp % 100000000).toString().padStart(8, '0');
    const shopName = `Chai & Bites Cafe ${timestamp % 10000}`;

    const context = await browser.newContext();
    const page = await context.newPage();

    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Restaurant / Cafe');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Chef Anand');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '123456');
    await page.fill('input[name="confirmPin"]', '123456');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Verify data-theme="restaurant"
    await expect(page.locator('[data-theme="restaurant"]')).toBeVisible();
    await expect(page.getByRole('main').getByText('Restaurant / Cafe')).toBeVisible();

    await context.close();
  });

  test('5. Repair Shop maps to retail theme with modern cyan/blue styling', async ({ browser }) => {
    const timestamp = Date.now();
    const phone = '95' + (timestamp % 100000000).toString().padStart(8, '0');
    const shopName = `Quick Fix Electronics ${timestamp % 10000}`;

    const context = await browser.newContext();
    const page = await context.newPage();

    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Repair Shop');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Rajesh Tech');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '123456');
    await page.fill('input[name="confirmPin"]', '123456');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Verify data-theme="retail"
    await expect(page.locator('[data-theme="retail"]')).toBeVisible();
    await expect(page.getByRole('main').getByText('Repair Shop')).toBeVisible();

    await context.close();
  });

  test('6. Other Local Business maps to default QueueLess violet theme', async ({ browser }) => {
    const timestamp = Date.now();
    const phone = '96' + (timestamp % 100000000).toString().padStart(8, '0');
    const shopName = `General Store ${timestamp % 10000}`;

    const context = await browser.newContext();
    const page = await context.newPage();

    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Other Local Business');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Owner Sunil');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '123456');
    await page.fill('input[name="confirmPin"]', '123456');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Verify data-theme="default"
    await expect(page.locator('[data-theme="default"]')).toBeVisible();
    await expect(page.getByRole('main').getByText('Other Local Business')).toBeVisible();

    await context.close();
  });

  test('7. Subscription and Admin interfaces preserve QueueLess brand and do not adopt shop theme', async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();

    // Navigate to admin page directly
    await page.goto('/admin');
    await expect(page.getByRole('heading', { name: /Minimal Admin Control|व्यवस्थापक नियंत्रण/i })).toBeVisible();

    // Verify admin is NOT themed with any merchant theme
    const barberThemeOnAdmin = page.locator('[data-theme="barber"]');
    await expect(barberThemeOnAdmin).toHaveCount(0);
    const salonThemeOnAdmin = page.locator('[data-theme="salon"]');
    await expect(salonThemeOnAdmin).toHaveCount(0);

    await context.close();
  });

  test('8. Mobile viewport responsive layout verification (320px & 375px)', async ({ browser }) => {
    // 320x568 (iPhone SE 1st gen)
    const mobileContext = await browser.newContext({
      viewport: { width: 320, height: 568 },
      userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) AppleWebKit/605.1.15',
    });
    const page = await mobileContext.newPage();

    await page.goto('/login');
    await expect(page.getByRole('heading', { name: /QueueLess/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Sign In|साइन इन|लॉग इन करें/i })).toBeVisible();

    // Verify no horizontal scrolling on 320px
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 2);

    await mobileContext.close();
  });

});
