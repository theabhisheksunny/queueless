import { test, expect } from '@playwright/test';

test.describe('Customer Live Queue Push Notifications (PWA + Web Push)', () => {
  const timestamp = Date.now();
  const testPhone = '98' + (timestamp % 100000000).toString().padStart(8, '0');
  const testPin = '789123';
  const shopName = `Push Barber ${timestamp % 1000}`;

  test('Customer QR journey displays push notification invitation and manages subscription lifecycle', async ({ page, context }) => {
    // Grant notification permission for http://localhost:8080 in browser context
    await context.grantPermissions(['notifications'], { origin: 'http://localhost:8080' });

    // Install Web Push & Service Worker mock before any page loads
    await context.addInitScript(() => {
      let currentPermission = 'default';
      try {
        Object.defineProperty(window.Notification, 'permission', {
          get: () => currentPermission,
          set: (v) => { currentPermission = v; },
          configurable: true,
        });
        (window.Notification as any).requestPermission = async () => {
          currentPermission = 'granted';
          return 'granted';
        };
      } catch (e) {
        console.error('Cannot override Notification.permission', e);
      }

      const fakeSub = {
        endpoint: 'https://fcm.googleapis.com/fcm/send/playwright-test-token',
        toJSON: () => ({
          endpoint: 'https://fcm.googleapis.com/fcm/send/playwright-test-token',
          keys: {
            p256dh: 'BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QT9Q0mA8GqkWfaoqq2m308wktA1qms7pQ',
            auth: 'tBHItJI5svbpez7KI4CCXg',
          },
        }),
        unsubscribe: async () => true,
      };

      const mockReg = {
        pushManager: {
          getSubscription: async () => null,
          subscribe: async () => fakeSub,
        },
      };

      if (!('serviceWorker' in navigator)) {
        (navigator as any).serviceWorker = {};
      }
      (navigator.serviceWorker as any).register = async () => mockReg;
      (navigator.serviceWorker as any).getRegistration = async () => mockReg;
      (navigator.serviceWorker as any).ready = Promise.resolve(mockReg);
    });

    // 1. Register a new shop owner with PIN
    await page.goto('/login');
    await page.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();

    await page.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await page.selectOption('select', 'Barber Shop');
    await page.fill('input[placeholder*="Ramesh Sharma"]', 'Test Owner');
    await page.fill('input[placeholder*="9876543210"]', testPhone);

    await page.fill('input[name="pin"]', testPin);
    await page.fill('input[name="confirmPin"]', testPin);

    await page.locator('button[type="submit"]').click();

    // 2. Open dashboard and get Join URL
    await expect(page.getByText('Customer Queue Register')).toBeVisible({ timeout: 10000 });
    await expect(page.getByText(shopName).first()).toBeVisible();

    const startQueueBtn = page.getByRole('button', { name: /Start Queue|कतार शुरू करें/i });
    if (await startQueueBtn.isVisible()) {
      await startQueueBtn.click();
    }

    const qrModalBtn = page.getByRole('button', { name: /Show QR Code|QR कोड दिखाएं/i });
    await qrModalBtn.click();

    const joinLinkHref = await page.getByTitle(/Open Customer View/i).getAttribute('href');
    expect(joinLinkHref).toBeTruthy();
    expect(joinLinkHref).toMatch(/\/join\/QS-[A-Za-z0-9]+/);
    await page.keyboard.press('Escape');

    // 3. Customer opens join URL
    await page.goto(joinLinkHref!);
    await expect(page.getByRole('heading', { name: /Get Your Token|अपना टोकन प्राप्त करें/i })).toBeVisible();

    await page.locator('input[type="text"]').first().fill('Suresh Raina');
    await page.getByRole('button', { name: /Get My Token|टोकन प्राप्त करें/i }).click();

    // 4. Customer arrives on tracking page
    await expect(page.getByText(/Live Token Register/i)).toBeVisible({ timeout: 10000 });
    await expect(page.getByText(/#001/)).toBeVisible();

    // 5. Verify push notification invitation is displayed
    const pushInvite = page.getByText(/Stay updated without keeping this page open/i);
    await expect(pushInvite).toBeVisible();

    const enableBtn = page.getByRole('button', { name: /Enable Live Notifications|लाइव सूचनाएं चालू करें/i });
    const notNowBtn = page.getByRole('button', { name: /Not Now|अभी नहीं/i });
    await expect(enableBtn).toBeVisible();
    await expect(notNowBtn).toBeVisible();

    // 6. Test "Not Now" dismisses the banner
    await notNowBtn.click();
    await expect(pushInvite).not.toBeVisible();

    // Restore banner via bell icon in header
    const bellRestoreBtn = page.locator('button[title="Enable Live Notifications"]');
    await expect(bellRestoreBtn).toBeVisible();
    await bellRestoreBtn.click();
    await expect(pushInvite).toBeVisible();

    // Click Enable Live Notifications
    await enableBtn.click();

    // 8. Verify active badge is shown
    const activeBadge = page.getByText(/Live Notifications Active 🔔|लाइव सूचनाएं सक्रिय हैं 🔔/i);
    await expect(activeBadge).toBeVisible({ timeout: 5000 });

    // 9. Test Turn Off
    const turnOffBtn = page.getByRole('button', { name: /Turn Off|बंद करें/i });
    await expect(turnOffBtn).toBeVisible();
    await turnOffBtn.click();

    // After turning off, active badge disappears
    await expect(activeBadge).not.toBeVisible();
  });

  test('PWA manifest and service worker are accessible publicly', async ({ request }) => {
    // 1. Verify Manifest
    const manifestRes = await request.get('/manifest.webmanifest');
    expect(manifestRes.status()).toBe(200);
    const manifestJson = await manifestRes.json();
    expect(manifestJson.name).toContain('QueueLess');
    expect(manifestJson.display).toBe('standalone');

    // 2. Verify Service Worker
    const swRes = await request.get('/sw.js');
    expect(swRes.status()).toBe(200);
    const swText = await swRes.text();
    expect(swText).toContain('queueless-queue-');
    expect(swText).toContain('notificationclick');

    // 3. Verify VAPID Public Key API
    const vapidRes = await request.get('/api/public/queue/vapid-public-key');
    expect(vapidRes.status()).toBe(200);
    const vapidJson = await vapidRes.json();
    expect(vapidJson.publicKey).toBeTruthy();
    expect(typeof vapidJson.publicKey).toBe('string');
  });
});
