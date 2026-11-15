import { test, expect } from '@playwright/test';

test.describe('Lock Business Category & ₹101 Category Change Fee E2E Tests', () => {

  test('1. Category is locked as read-only after registration; owner submits ₹101 change request; admin approves', async ({ browser }) => {
    const timestamp = Date.now();
    const phone = '93' + (timestamp % 100000000).toString().padStart(8, '0');
    const email = `catowner_${timestamp}@queueless.com`;
    const shopName = `Elite Style Salon ${timestamp % 10000}`;

    // ==========================================
    // Step 1: Owner Registration with initial category "Barber Shop"
    // ==========================================
    const context = await browser.newContext();
    const page = await context.newPage();

    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Barber Shop');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Rohit Verma');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '123456');
    await page.fill('input[name="confirmPin"]', '123456');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // ==========================================
    // Step 2: Navigate to Settings & verify category is read-only
    // ==========================================
    await page.goto('/settings');
    await expect(page.getByRole('heading', { name: /Settings|सेटिंग्स/i })).toBeVisible();

    // Verify category is locked
    await expect(page.getByRole('main').getByText('Barber Shop')).toBeVisible();
    await expect(page.getByText(/Registered and locked|पंजीकृत और लॉक/i)).toBeVisible();
    await expect(page.getByText(/locked after registration/i)).toBeVisible();

    // Verify there is no normal <select> inside the shop profile form for category
    const categorySelectInForm = page.locator('form select');
    await expect(categorySelectInForm).toHaveCount(0);

    // ==========================================
    // Step 3: Ordinary settings update modifies name, preserves category
    // ==========================================
    const updatedName = `${shopName} Updated`;
    await page.fill('input[required]', updatedName);
    await page.getByRole('button', { name: /Save Settings|सेटिंग्स सहेजें/i }).click();
    await expect(page.getByText(/Settings updated successfully|सफलतापूर्वक अपडेट/i)).toBeVisible();
    await expect(page.getByRole('main').getByText('Barber Shop')).toBeVisible();

    // ==========================================
    // Step 4: Open Category Change Request (₹101) Modal
    // ==========================================
    await page.getByRole('button', { name: /Request Business Category Change — ₹101/i }).click();

    await expect(page.getByRole('heading', { name: /Change Business Category \(₹101 Fee\)/i })).toBeVisible();
    await expect(page.getByText('₹101', { exact: true })).toBeVisible();
    await expect(page.getByText(/queueless@upi/i)).toBeVisible();

    // Select new category: "Clinic & Doctor"
    await page.locator('form select').selectOption('Clinic & Doctor');

    // Enter 12-digit UTR
    const utrNumber = '9876543210' + (timestamp % 100).toString().padStart(2, '0');
    await page.fill('input[placeholder*="432198765432"]', utrNumber);

    await page.getByRole('button', { name: /Submit Payment Reference/i }).click();

    // ==========================================
    // Step 5: Verify request status is Pending Verification & category unchanged
    // ==========================================
    await expect(page.getByText(/Category change request submitted!/i)).toBeVisible();
    await expect(page.getByText(/Pending Verification/i)).toBeVisible();
    await expect(page.getByText(/Clinic & Doctor/i)).toBeVisible();

    // Current category MUST still be "Barber Shop"
    await expect(page.getByRole('main').getByText('Barber Shop')).toBeVisible();

    // ==========================================
    // Step 6: Admin approves request on /admin
    // ==========================================
    const adminContext = await browser.newContext();
    const adminPage = await adminContext.newPage();

    await adminPage.goto('/admin');
    await adminPage.getByRole('button', { name: /Access Admin Panel|प्रवेश करें/i }).click();

    await expect(adminPage.getByText(/Category Change Requests \(₹101\)/i)).toBeVisible();
    await expect(adminPage.getByText(shopName).first()).toBeVisible();
    await expect(adminPage.getByText(utrNumber)).toBeVisible();

    // Click Approve on the request
    await adminPage.getByRole('button', { name: /Approve|स्वीकार करें/i }).first().click();
    await expect(adminPage.getByText(/approved successfully! Shop category updated/i)).toBeVisible();

    await adminContext.close();

    // ==========================================
    // Step 7: Owner page refreshes & confirms category is now "Clinic & Doctor"
    // ==========================================
    await page.reload();
    await expect(page.getByText('Clinic & Doctor', { exact: true })).toBeVisible();
    await expect(page.getByText(/Approved|स्वीकृत/i)).toBeVisible();

    await context.close();
  });

  test('2. Admin rejection leaves original category unchanged and shows clear rejection reason to owner', async ({ browser }) => {
    const timestamp = Date.now();
    const phone = '92' + (timestamp % 100000000).toString().padStart(8, '0');
    const email = `reject_owner_${timestamp}@queueless.com`;
    const shopName = `Glow Spa Center ${timestamp % 10000}`;

    // 1. Owner registers with category "Salon & Spa"
    const context = await browser.newContext();
    const page = await context.newPage();

    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|साइन अप/i }).click();

    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Salon & Spa');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Pooja Nair');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '123456');
    await page.fill('input[name="confirmPin"]', '123456');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('Customer Queue Register')).toBeVisible();

    // 2. Go to Settings and submit request to change to "Restaurant / Cafe"
    await page.goto('/settings');
    await expect(page.getByRole('main').getByText('Salon & Spa')).toBeVisible();

    await page.getByRole('button', { name: /Request Business Category Change — ₹101/i }).click();
    await page.locator('form select').selectOption('Restaurant / Cafe');

    const fakeUtr = 'INV_UTR_' + (timestamp % 10000000);
    await page.fill('input[placeholder*="432198765432"]', fakeUtr);
    await page.getByRole('button', { name: /Submit Payment Reference/i }).click();

    await expect(page.getByText(/Pending Verification/i)).toBeVisible();

    // 3. Admin rejects the request with a reason
    const adminContext = await browser.newContext();
    const adminPage = await adminContext.newPage();

    // Setup dialog listener to accept prompt with rejection reason
    adminPage.on('dialog', async (dialog) => {
      await dialog.accept('Invalid UTR reference: Payment not received in bank account');
    });

    await adminPage.goto('/admin');
    await adminPage.getByRole('button', { name: /Access Admin Panel|प्रवेश करें/i }).click();

    await expect(adminPage.getByText(shopName).first()).toBeVisible();
    await adminPage.getByRole('button', { name: /Reject|अस्वीकार करें/i }).first().click();
    await expect(adminPage.getByText(/Category change request rejected/i)).toBeVisible();

    await adminContext.close();

    // 4. Owner reloads: Category is STILL "Salon & Spa" and displays REJECTED banner with reason
    await page.reload();
    await expect(page.getByRole('main').getByText('Salon & Spa')).toBeVisible();
    await expect(page.getByText(/Rejected|अस्वीकृत/i)).toBeVisible();
    await expect(page.getByText(/Invalid UTR reference/i)).toBeVisible();

    await context.close();
  });
});
