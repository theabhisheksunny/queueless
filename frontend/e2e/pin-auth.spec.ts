import { test, expect } from '@playwright/test';

test.describe('Mobile Number & 6-Digit PIN Authentication', () => {
  test('1. Simple Shop Owner Registration with Mobile Number and 6-Digit PIN (No email needed)', async ({ page }) => {
    const timestamp = Date.now();
    const phone = '98' + (timestamp % 100000000).toString().padStart(8, '0');
    const shopName = `Sharma Barber Shop ${timestamp % 1000}`;

    await page.goto('/login');
    await expect(page.locator('h1')).toContainText('QueueLess');

    // Switch to Register tab
    await page.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();

    // Fill registration details
    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Barber Shop');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Ramesh Sharma');
    await page.fill('input[placeholder*="9876543210"]', phone);

    // Send OTP
    await page.getByRole('button', { name: /Send OTP|ओटीपी भेजें/i }).click();
    await expect(page.getByRole('button', { name: /Resend in|पुनः भेजें/i })).toBeVisible();

    // Enter 6-digit PIN & Confirm PIN
    await page.fill('input[name="pin"]', '456789');
    await page.fill('input[name="confirmPin"]', '456789');

    // Submit registration
    await page.locator('button[type="submit"]').click();

    // Verify redirected to owner dashboard
    await expect(page.getByText('Customer Queue Register')).toBeVisible();
    await expect(page.getByText(shopName).first()).toBeVisible();
    await expect(page.getByRole('button', { name: /Start Queue|कतार शुरू करें/i })).toBeVisible();
  });

  test('2. Routine Login with Mobile Number and 6-Digit PIN (No SMS OTP required)', async ({ page }) => {
    const timestamp = Date.now();
    const phone = '97' + (timestamp % 100000000).toString().padStart(8, '0');
    const shopName = `Quick Fix Garage ${timestamp % 1000}`;

    // 1. Register account
    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();
    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Automobile Service');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Sunil Auto');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page.fill('input[name="pin"]', '112233');
    await page.fill('input[name="confirmPin"]', '112233');
    await page.locator('button[type="submit"]').click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // 2. Log out
    await page.getByRole('button', { name: /Logout|लॉगआउट/i }).click();
    await expect(page.locator('h1')).toContainText('QueueLess');

    // 3. Routine login using mobile number + 6-digit PIN
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '112233');
    await page.locator('button[type="submit"]').click();

    // Land directly on dashboard without any OTP prompt
    await expect(page.getByText('Customer Queue Register')).toBeVisible();
    await expect(page.getByText(shopName).first()).toBeVisible();
  });

  test('3. Brute-Force Lockout Protection: 5 consecutive wrong PIN entries trigger temporary lockout', async ({ page }) => {
    const timestamp = Date.now();
    const phone = '96' + (timestamp % 100000000).toString().padStart(8, '0');

    // 1. Register owner
    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();
    await page.fill('input[placeholder*="Royal Hair Salon"]', `Lockout Test Shop ${timestamp % 1000}`);
    await page.selectOption('select', 'Repair Shop');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Lockout Target');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page.fill('input[name="pin"]', '998877');
    await page.fill('input[name="confirmPin"]', '998877');
    await page.locator('button[type="submit"]').click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // 2. Logout
    await page.getByRole('button', { name: /Logout|लॉगआउट/i }).click();

    // 3. Enter wrong PIN 5 times
    await page.fill('input[placeholder*="9876543210"]', phone);

    for (let attempt = 1; attempt <= 4; attempt++) {
      await page.fill('input[name="pin"]', '000000');
      await page.locator('button[type="submit"]').click();
      await expect(page.getByText(new RegExp(`${5 - attempt} attempt\\(s\\) remaining`, 'i'))).toBeVisible();
    }

    // 5th attempt triggers lockout
    await page.fill('input[name="pin"]', '000000');
    await page.locator('button[type="submit"]').click();
    await expect(page.getByText(/Account locked for 15 minutes/i)).toBeVisible();

    // 6th attempt even with CORRECT PIN is blocked by lockout
    await page.fill('input[name="pin"]', '998877');
    await page.locator('button[type="submit"]').click();
    await expect(page.getByText(/Account temporarily locked/i)).toBeVisible();
  });

  test('4. Forgot PIN Recovery via Mobile OTP with Manual SIM Recovery Help Guidance', async ({ page }) => {
    const timestamp = Date.now();
    const phone = '95' + (timestamp % 100000000).toString().padStart(8, '0');
    const shopName = `Recovery Clinic ${timestamp % 1000}`;

    // 1. Register owner
    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();
    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Clinic & Doctor');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Dr. Recovery');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page.getByRole('button', { name: /Resend in/i })).toBeVisible();

    await page.fill('input[name="pin"]', '123123');
    await page.fill('input[name="confirmPin"]', '123123');
    await page.locator('button[type="submit"]').click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // 2. Logout
    await page.getByRole('button', { name: /Logout|लॉगआउट/i }).click();

    // 3. Click Forgot PIN
    await page.getByRole('button', { name: /Forgot PIN\?|पिन भूल गए\?/i }).click();
    await expect(page.getByRole('heading', { name: /Reset.*PIN|पिन रीसेट/i })).toBeVisible();

    // Verify clear SIM-loss guidance for non-technical users
    await expect(page.getByText(/Lost.*SIM card or not receiving OTP/i)).toBeVisible();
    await expect(page.getByText(/\+91 98765 43210/i)).toBeVisible();

    // Send OTP for recovery
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.getByRole('button', { name: /Send OTP/i }).click();
    await expect(page.getByRole('button', { name: /Resend in/i })).toBeVisible();

    // Set new PIN
    await page.fill('input[name="pin"]', '654321');
    await page.fill('input[name="confirmPin"]', '654321');
    await page.locator('button[type="submit"]').click();

    // Success notice displayed
    await expect(page.getByText(/PIN reset successfully/i)).toBeVisible();

    // 4. Log in with the newly reset PIN
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '654321');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();
    await expect(page.getByText(shopName).first()).toBeVisible();
  });

  test('5. Hindi and English Language Localization for Non-Technical Users', async ({ page }) => {
    await page.goto('/login');

    // Default English
    await expect(page.getByRole('button', { name: /Sign In/i })).toBeVisible();
    await expect(page.getByText(/Simple QR-Based Digital Token Register/i)).toBeVisible();

    // Switch to Hindi
    await page.getByRole('button', { name: /हिंदी/i }).click();

    // Verify Hindi labels
    await expect(page.getByRole('button', { name: /साइन इन करें/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /खाता बनाएं/i })).toBeVisible();
    await expect(page.getByText(/6-अंकों का गुप्त पिन/i)).toBeVisible();
    await expect(page.getByText(/पिन भूल गए\?/i)).toBeVisible();

    // Switch back to English
    await page.getByRole('button', { name: /English/i }).click();
    await expect(page.getByRole('button', { name: /Sign In/i })).toBeVisible();
  });
});
