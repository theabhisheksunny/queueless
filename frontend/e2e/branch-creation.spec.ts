import { test, expect } from '@playwright/test';

test.describe('Branch Creation & Multi-Branch Conflict Resolution E2E', () => {

  test('Owner opens Add New Branch modal and creates branches with identical and distinct names without conflict', async ({ page }) => {
    const timestamp = Date.now();
    const phone = '98' + (timestamp % 100000000).toString().padStart(8, '0');

    // 1. Register new owner
    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();

    const shopName = `Apex Flagship ${timestamp % 1000}`;
    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Barber Shop');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Branch Test Owner');
    await page.fill('input[placeholder*="9876543210"]', phone);
    await page.fill('input[name="pin"]', '123456');
    await page.fill('input[name="confirmPin"]', '123456');

    await page.locator('button[type="submit"]').click();
    await expect(page.getByText('Customer Queue Register')).toBeVisible({ timeout: 10000 });
    await expect(page.getByText(shopName).first()).toBeVisible();

    // 2. Open Branch Switcher and click "Add New Branch"
    await page.locator(`button:has-text("${shopName}")`).first().click();
    await page.getByRole('button', { name: /Add New Branch/i }).click();

    // 3. Verify modal is visible
    await expect(page.locator('text=Add New Branch / Shop')).toBeVisible();
    await expect(page.locator('text=₹99 per additional branch')).toBeVisible();

    // 4. Fill in second branch
    await page.fill('input[placeholder*="Downtown Mall"]', 'Apex Downtown');
    await page.fill('input[placeholder*="Shop No"]', '123 Main Street, Sector 18');
    await page.fill('input[placeholder="9876543210"]', '9811223344');

    // 5. Submit branch creation
    await page.getByRole('button', { name: /Create Branch/i }).click();

    // 6. Verify NO database conflict error banner appears
    await expect(page.locator('text=A database record conflict occurred')).not.toBeVisible();

    // 7. Verify modal closes and active branch switches to "Apex Downtown"
    await expect(page.locator('text=Add New Branch / Shop')).not.toBeVisible();
    await expect(page.locator('button:has-text("Apex Downtown")').first()).toBeVisible({ timeout: 5000 });

    // 8. Open modal again and create a branch with an identical name "Apex Downtown" (different location)
    await page.locator('button:has-text("Apex Downtown")').first().click();
    await page.getByRole('button', { name: /Add New Branch/i }).click();

    await expect(page.locator('text=Add New Branch / Shop')).toBeVisible();
    await page.fill('input[placeholder*="Downtown Mall"]', 'Apex Downtown'); // Identical name
    await page.fill('input[placeholder*="Shop No"]', '456 Station Road, Sector 62');
    await page.fill('input[placeholder="9876543210"]', '9811223355');

    await page.getByRole('button', { name: /Create Branch/i }).click();
    await expect(page.locator('text=A database record conflict occurred')).not.toBeVisible();
    await expect(page.locator('text=Add New Branch / Shop')).not.toBeVisible();

    // 9. Verify branch switcher now contains both branches and primary
    await page.locator('button:has-text("Apex Downtown")').first().click();
    await expect(page.locator(`text=${shopName}`)).toBeVisible();
    await expect(page.locator('text=Primary')).toBeVisible();
  });
});
