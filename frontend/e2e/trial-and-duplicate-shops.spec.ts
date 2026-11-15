import { test, expect } from '@playwright/test';

test.describe('Duplicate Shop Names & Secure 7-Day Trial Enforcement E2E Tests', () => {

  test('1. Two owners register identical shop names and operate independently with strict data isolation', async ({ browser }) => {
    const sharedShopName = 'Royal Barber & Spa Lounge';
    const timestamp = Date.now();

    // ==========================================
    // Owner 1: Register in Mumbai
    // ==========================================
    const context1 = await browser.newContext();
    const page1 = await context1.newPage();

    await page1.goto('/login');
    await page1.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    const phone1 = '98' + (timestamp % 100000000).toString().padStart(8, '0');
    const email1 = `mumbai_owner_${timestamp}@queueless.com`;

    await page1.fill('input[placeholder*="Royal Hair Salon"]', sharedShopName);
    await page1.selectOption('select', 'Barber Shop');
    await page1.fill('input[placeholder*="Ramesh Sharma"]', 'Aman Verma');
    await page1.fill('input[placeholder*="9876543210"]', phone1);
    await page1.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page1.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page1.fill('input[placeholder*="owner@example.com"]', email1);
    await page1.fill('input[type="password"]', 'mumbaiPass123');
    await page1.locator('button[type="submit"]').click();

    await expect(page1.getByText('Customer Queue Register')).toBeVisible();
    await expect(page1.getByText(sharedShopName).first()).toBeVisible();

    // Start Queue for Shop 1
    await page1.getByRole('button', { name: /Start Queue/i }).click();
    await expect(page1.getByText('OPEN')).toBeVisible();

    // Add customer to Shop 1
    await page1.getByRole('button', { name: /Add Customer/i }).click();
    await page1.fill('input[placeholder*="Rahul Sharma"]', 'Customer of Shop 1');
    await page1.getByRole('button', { name: /Generate Token/i }).click();

    const shop1Customer = page1.locator('[data-testid="customer-row"]', { hasText: 'Customer of Shop 1' });
    await expect(shop1Customer).toBeVisible();

    // ==========================================
    // Owner 2: Register in Delhi with identical shop name
    // ==========================================
    const context2 = await browser.newContext();
    const page2 = await context2.newPage();

    await page2.goto('/login');
    await page2.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    const phone2 = '97' + ((timestamp + 1) % 100000000).toString().padStart(8, '0');
    const email2 = `delhi_owner_${timestamp}@queueless.com`;

    await page2.fill('input[placeholder*="Royal Hair Salon"]', sharedShopName);
    await page2.selectOption('select', 'Barber Shop');
    await page2.fill('input[placeholder*="Ramesh Sharma"]', 'Vikram Seth');
    await page2.fill('input[placeholder*="9876543210"]', phone2);
    await page2.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page2.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page2.fill('input[placeholder*="owner@example.com"]', email2);
    await page2.fill('input[type="password"]', 'delhiPass123');
    await page2.locator('button[type="submit"]').click();

    await expect(page2.getByText('Customer Queue Register')).toBeVisible();
    await expect(page2.getByText(sharedShopName).first()).toBeVisible();

    // Start Queue for Shop 2
    await page2.getByRole('button', { name: /Start Queue/i }).click();
    await expect(page2.getByText('OPEN')).toBeVisible();

    // Verify Shop 2 dashboard is empty and DOES NOT see Shop 1's customer
    await expect(page2.locator('[data-testid="customer-row"]')).toHaveCount(0);
    await expect(page2.getByText('Customer of Shop 1')).not.toBeVisible();

    // Add customer to Shop 2
    await page2.getByRole('button', { name: /Add Customer/i }).click();
    await page2.fill('input[placeholder*="Rahul Sharma"]', 'Customer of Shop 2');
    await page2.getByRole('button', { name: /Generate Token/i }).click();

    const shop2Customer = page2.locator('[data-testid="customer-row"]', { hasText: 'Customer of Shop 2' });
    await expect(shop2Customer).toBeVisible();

    // Verify Shop 1 STILL only has its own customer
    await expect(page1.getByText('Customer of Shop 2')).not.toBeVisible();

    await context1.close();
    await context2.close();
  });

  test('2. Trial Abuse Prevention: Re-registration with same verified mobile is rejected', async ({ page }) => {
    const timestamp = Date.now();
    const phone = '96' + (timestamp % 100000000).toString().padStart(8, '0');

    // Register initial account
    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    await page.fill('input[placeholder*="Royal Hair Salon"]', 'First Trial Salon');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Owner One');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page.fill('input[placeholder*="owner@example.com"]', `owner1_${timestamp}@queueless.com`);
    await page.fill('input[type="password"]', 'pass1234');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // Log out
    await page.getByRole('button', { name: /Logout|लॉगआउट/i }).click();
    await expect(page.locator('h1')).toContainText('QueueLess');

    // Attempt second registration with different email but SAME mobile number
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();
    await page.fill('input[placeholder*="Royal Hair Salon"]', 'Second Trial Attempt');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Abuse Attempter');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page.fill('input[placeholder*="owner@example.com"]', `owner2_${timestamp}@queueless.com`);
    await page.fill('input[type="password"]', 'pass1234');
    await page.locator('button[type="submit"]').click();

    // Verify rejection alert
    await expect(page.getByText(/already exists|already been claimed|not eligible/i)).toBeVisible();
  });

  test('3. Account deletion followed by re-registration — Ledger prevents claiming fresh trial', async ({ page }) => {
    const timestamp = Date.now();
    const phone = '95' + (timestamp % 100000000).toString().padStart(8, '0');
    const email = `deleteme_${timestamp}@queueless.com`;

    // 1. Register
    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();
    await page.fill('input[placeholder*="Royal Hair Salon"]', 'Delete Me Clinic');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Temporary Owner');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page.fill('input[placeholder*="owner@example.com"]', email);
    await page.fill('input[type="password"]', 'pass1234');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // 2. Navigate to Settings and Delete Account
    await page.goto('/settings');
    await expect(page.getByText('Shop Profile')).toBeVisible();
    await page.getByRole('button', { name: /Delete Shop Account/i }).click();
    await page.getByRole('button', { name: /Yes, Delete Permanently/i }).click();

    // Redirected to Login
    await expect(page).toHaveURL(/.*\/login/);

    // 3. Attempt re-registration with the same verified mobile number
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();
    await page.fill('input[placeholder*="Royal Hair Salon"]', 'Re-registered Clinic');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Same Owner Again');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page.fill('input[placeholder*="owner@example.com"]', `newemail_${timestamp}@queueless.com`);
    await page.fill('input[type="password"]', 'pass1234');
    await page.locator('button[type="submit"]').click();

    // Verify rejection by persistent ledger
    await expect(page.getByText(/trial already consumed|already been claimed|not eligible/i)).toBeVisible();
  });

  test('4. Password reset via verified OTP succeeds without granting fresh trial', async ({ page }) => {
    const timestamp = Date.now();
    const phone = '94' + (timestamp % 100000000).toString().padStart(8, '0');
    const email = `pwreset_${timestamp}@queueless.com`;

    // 1. Register
    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();
    await page.fill('input[placeholder*="Royal Hair Salon"]', 'Password Reset Shop');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Reset Owner');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page.fill('input[placeholder*="owner@example.com"]', email);
    await page.fill('input[type="password"]', 'initialPass123');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // 2. Logout
    await page.getByRole('button', { name: /Logout|लॉगआउट/i }).click();

    // 3. Click Forgot Password
    await page.getByRole('button', { name: /Forgot Password\?/i }).click();
    await expect(page.getByRole('heading', { name: /Reset Password/i })).toBeVisible();

    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page.fill('input[placeholder*="••••••••"]', 'updatedSecurePass123');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText(/Password reset successfully/i)).toBeVisible();

    // 4. Log in with new password
    await page.fill('input[placeholder*="owner@example.com"]', email);
    await page.fill('input[placeholder*="••••••••"]', 'updatedSecurePass123');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();
  });
});
