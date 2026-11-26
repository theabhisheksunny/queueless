import { test, expect } from '@playwright/test';

test.describe('QueueLess — Global User-Friendly Error Handling, Navigation & Deep Linking E2E', () => {

  const testPhone = '9811000001';
  const testPin = '123456';

  // Precondition: Ensure test owner 9811000001 is registered before returnUrl restore tests
  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto('/login');
      // Attempt login first to see if already registered
      await page.fill('input[type="tel"]', testPhone);
      await page.fill('input[name="pin"], input[type="password"]', testPin);
      await page.locator('button[type="submit"]').click();

      // Check if login succeeds
      try {
        await page.waitForURL('**/', { timeout: 3000 });
        await page.evaluate(() => localStorage.clear());
        await context.close();
        return;
      } catch {
        // Not yet registered or credentials didn't match; proceed to register
      }

      await page.goto('/login');
      await page.getByRole('button', { name: /Create Account|साइन अप|खाता बनाएं/i }).click();
      await page.fill('input[placeholder*="Royal Hair Salon"]', 'Route Guard Master Shop');
      await page.selectOption('select', 'Barber Shop');
      await page.fill('input[placeholder*="Ramesh Sharma"]', 'Route Tester');
      await page.fill('input[placeholder*="9876543210"]', testPhone);
      await page.fill('input[name="pin"]', testPin);
      await page.fill('input[name="confirmPin"]', testPin);
      await page.locator('button[type="submit"]').click();
      await page.waitForTimeout(2000);
    } catch {
      // Account established or already exists
    } finally {
      await context.close();
    }
  });

  test('1 & 2. Session expires on /settings -> preserves returnUrl and restores /settings after login', async ({ page }) => {
    await page.goto('/login');
    await page.evaluate(() => localStorage.clear());

    // Direct navigation to protected /settings while logged out
    await page.goto('/settings');

    // Route guard must intercept and preserve original target in redirect query
    await expect(page).toHaveURL(/\/login\?redirect=%2Fsettings/);

    // Routine login with mobile number + 6-digit PIN
    await page.fill('input[type="tel"]', testPhone);
    await page.fill('input[name="pin"], input[type="password"]', testPin);
    await page.locator('button[type="submit"]').click();

    // After successful PIN login, user MUST be restored to original destination (/settings)
    await page.waitForURL('**/settings', { timeout: 10000 });
    expect(page.url()).toContain('/settings');
  });

  test('3 & 4. Session expires on /staff -> preserves returnUrl and restores /staff after login', async ({ page }) => {
    await page.goto('/login');
    await page.evaluate(() => localStorage.clear());

    // Direct navigation to protected /staff while logged out
    await page.goto('/staff');

    // Route guard preserves /staff destination
    await expect(page).toHaveURL(/\/login\?redirect=%2Fstaff/);

    // Routine login with mobile number + 6-digit PIN
    await page.fill('input[type="tel"]', testPhone);
    await page.fill('input[name="pin"], input[type="password"]', testPin);
    await page.locator('button[type="submit"]').click();

    // Restores /staff
    await page.waitForURL('**/staff', { timeout: 10000 });
    expect(page.url()).toContain('/staff');
  });

  test('5 & 6. Staff account opening owner-only page (/staff) receives friendly 403 Access Denied screen', async ({ browser }) => {
    const timestamp = Date.now();
    const ownerPhone = '98' + (timestamp % 100000000).toString().padStart(8, '0');
    const staffPhone = '91' + ((timestamp + 5) % 100000000).toString().padStart(8, '0');
    const shopName = `Staff Security Shop ${timestamp % 1000}`;

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();

    // 1. Register owner
    await ownerPage.goto('/login');
    await ownerPage.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();
    await ownerPage.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await ownerPage.selectOption('select', 'Barber Shop');
    await ownerPage.fill('input[placeholder*="Ramesh Sharma"]', 'Owner Test');
    await ownerPage.fill('input[placeholder*="9876543210"]', ownerPhone);
    await ownerPage.fill('input[name="pin"]', '123456');
    await ownerPage.fill('input[name="confirmPin"]', '123456');
    await ownerPage.locator('button[type="submit"]').click();
    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();

    // 2. Add staff member
    await ownerPage.goto('/staff');
    await expect(ownerPage.getByRole('heading', { name: /Staff & User Seats/i })).toBeVisible();
    await ownerPage.getByRole('button', { name: /Add Staff/i }).first().click();
    await ownerPage.fill('input[placeholder*="Ramesh Kumar"]', 'Staff Operator');
    await ownerPage.fill('input[placeholder="9876543210"]', staffPhone);
    await ownerPage.fill('input[placeholder="••••••"]', '123456');
    await ownerPage.getByRole('button', { name: /Create Staff User/i }).click();
    await expect(ownerPage.getByText('Staff Operator').first()).toBeVisible();
    await expect(ownerPage.getByText(/Awaiting Enrollment/i).first()).toBeVisible();

    // 3. Staff Context: First-time staff enrollment
    const staffContext = await browser.newContext();
    const staffPage = await staffContext.newPage();
    await staffPage.goto('/login');
    await staffPage.fill('input[placeholder*="9876543210"]', staffPhone);
    await staffPage.fill('input[name="pin"]', '123456');
    await staffPage.locator('button[type="submit"]').click();

    // Redirected to First-time enrollment mode
    await expect(staffPage.getByText(/First-Time Device Enrollment/i)).toBeVisible();

    // Fill new permanent PIN: 654321
    const pinInputs = staffPage.locator('input[type="password"]');
    await pinInputs.nth(1).fill('654321');
    await pinInputs.nth(2).fill('654321');
    await staffPage.getByRole('button', { name: /Enroll Device & Activate/i }).click();

    // Staff lands on Dashboard
    await expect(staffPage.getByText('Customer Queue Register')).toBeVisible();

    // 4. Staff member attempts to navigate to owner-only staff management (/staff)
    await staffPage.goto('/staff');

    // Must show friendly 403 Access Denied screen
    await expect(staffPage.getByText(/403 — Access Restricted|403 — अनुमति नहीं है/i)).toBeVisible();
    await expect(staffPage.getByText(/Shop Owner Access Only|केवल दुकान मालिक के लिए/i)).toBeVisible();
    await expect(staffPage.getByText(/reserved exclusively for the shop owner account/i)).toBeVisible();

    // Must NOT disclose another owner's account or system stack trace
    await expect(staffPage.getByText('Exception')).not.toBeVisible();
    await expect(staffPage.getByText('org.springframework')).not.toBeVisible();

    // Action button to return to authorized dashboard
    const returnBtn = staffPage.getByRole('button', { name: /Go to Dashboard|डैशबोर्ड पर जाएं/i });
    await expect(returnBtn).toBeVisible();
    await returnBtn.click();
    await expect(staffPage.getByText('Customer Queue Register')).toBeVisible();

    await ownerContext.close();
    await staffContext.close();
  });

  test('7 & 8. Nonexistent route (/setting typo or invalid path) renders branded 404 screen', async ({ page }) => {
    await page.goto('/setting');

    // Should display branded 404 page
    await expect(page.getByText(/404 — Page Not Found|404 — त्रुटि/i)).toBeVisible();
    await expect(page.getByText(/Lost in the Queue\?|यह पृष्ठ उपलब्ध नहीं है/i)).toBeVisible();

    // Navigation buttons must be present
    const goBackBtn = page.getByRole('button', { name: /Go Back|पीछे जाएं/i });
    const actionBtn = page.getByRole('button', { name: /Sign In|लॉगिन करें|Go to Dashboard|डैशबोर्ड पर जाएं/i });
    await expect(goBackBtn).toBeVisible();
    await expect(actionBtn).toBeVisible();

    // Clicking Go to Dashboard / Sign In navigates safely
    await actionBtn.click();
    await expect(page).toHaveURL(/\/(login)?$/);
  });

  test('9, 10 & 11. Open redirect protections: external, protocol-relative, and backslash returnUrls are rejected', async ({ page }) => {
    // 9. External URL (https://evil.com)
    await page.goto('/login?redirect=https%3A%2F%2Fevil.com');
    await page.fill('input[type="tel"]', testPhone);
    await page.fill('input[name="pin"], input[type="password"]', testPin);
    await page.locator('button[type="submit"]').click();
    // Must fall back safely to dashboard '/', NOT evil.com
    await page.waitForURL('**/', { timeout: 10000 });
    expect(page.url()).not.toContain('evil.com');

    // 10. Protocol-relative URL (//evil.com)
    await page.goto('/login');
    await page.evaluate(() => localStorage.clear());
    await page.goto('/login?redirect=%2F%2Fevil.com');
    await page.fill('input[type="tel"]', testPhone);
    await page.fill('input[name="pin"], input[type="password"]', testPin);
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/', { timeout: 10000 });
    expect(page.url()).not.toContain('evil.com');

    // 11. Backslash trickery (/\\evil.com)
    await page.goto('/login');
    await page.evaluate(() => localStorage.clear());
    await page.goto('/login?redirect=%2F%5Cevil.com');
    await page.fill('input[type="tel"]', testPhone);
    await page.fill('input[name="pin"], input[type="password"]', testPin);
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/', { timeout: 10000 });
    expect(page.url()).not.toContain('evil.com');
  });

  test('12 & 13. Session expired banner displays friendly empathetic guidance', async ({ page }) => {
    await page.goto('/login?session_expired=true');

    await expect(
      page.getByText(/Your session has ended\. Please sign in with your 6-digit PIN to continue/i)
    ).toBeVisible();
  });

  test('20 & 21. Browser refresh on deep link and back/forward navigation preserves route', async ({ page }) => {
    // Log in first
    await page.goto('/login');
    await page.fill('input[type="tel"]', testPhone);
    await page.fill('input[name="pin"], input[type="password"]', testPin);
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/', { timeout: 10000 });

    // Navigate to /settings
    await page.goto('/settings');
    await expect(page.getByText('Shop Settings')).toBeVisible();

    // Reload page (browser refresh)
    await page.reload();

    // User must remain on /settings and not be incorrectly kicked back to /
    await expect(page).toHaveURL(/\/settings$/);
    await expect(page.getByText('Shop Settings')).toBeVisible();

    // Browser Back navigation to /
    await page.goBack();
    await expect(page).toHaveURL(/\/(#.*)?$/);

    // Browser Forward navigation to /settings
    await page.goForward();
    await expect(page).toHaveURL(/\/settings$/);
  });
});
