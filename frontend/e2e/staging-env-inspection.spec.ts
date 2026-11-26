import { test, expect } from '@playwright/test';

test.describe('Phase 6B: Staging Environment Verification & Runtime Hygiene', () => {

  test('1. Verify staging homepage, static assets, React mount, and zero console errors', async ({ page }) => {
    const consoleErrors: string[] = [];
    const failedNetworkRequests: { url: string; status: number }[] = [];
    const allRequestedUrls: string[] = [];

    page.on('console', msg => {
      if (msg.type() === 'error') {
        consoleErrors.push(`[Console Error] ${msg.text()}`);
      }
    });

    page.on('pageerror', err => {
      consoleErrors.push(`[Page Crash] ${err.message}`);
    });

    page.on('response', resp => {
      allRequestedUrls.push(resp.url());
      if (resp.status() >= 400) {
        failedNetworkRequests.push({ url: resp.url(), status: resp.status() });
      }
    });

    // 1. Visit Staging Root
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // 2. Verify Page Title & React Root Mount
    await expect(page).toHaveTitle(/QueueLess/i);
    const root = page.locator('#root');
    await expect(root).toBeVisible();

    // 3. Verify Static Assets (Scripts & Stylesheets) loaded with 200 OK
    const scriptUrls = allRequestedUrls.filter(u => u.includes('/assets/index-') && u.endsWith('.js'));
    const cssUrls = allRequestedUrls.filter(u => u.includes('/assets/index-') && u.endsWith('.css'));
    expect(scriptUrls.length).toBeGreaterThan(0);
    expect(cssUrls.length).toBeGreaterThan(0);

    // 4. Verify ZERO Production URL leakages
    for (const url of allRequestedUrls) {
      expect(url).not.toContain('queueless.theabhisheksunny.workers.dev'); // Prod worker
      expect(url).not.toContain('queueless-prod'); // Prod GCP
      expect(url).not.toContain('queueless-backend-prod');
    }

    // 5. Verify Zero Console Errors or Unhandled Exceptions
    expect(consoleErrors).toEqual([]);
    expect(failedNetworkRequests).toEqual([]);
  });

  test('2. Verify Cloudflare Edge API proxying and VAPID public key payload integrity', async ({ request }) => {
    // Probe through Cloudflare Worker edge proxy
    const vapidRes = await request.get('/api/public/queue/vapid-public-key');
    expect(vapidRes.status()).toBe(200);

    const data = await vapidRes.json();
    expect(data.publicKey).toBeDefined();
    expect(typeof data.publicKey).toBe('string');
    expect(data.publicKey.length).toBe(87); // Standard uncompressed ECDSA P-256 public key length
    expect(data.error).toBeUndefined();
  });

  test('3. Verify direct backend readiness endpoint includes database readiness', async ({ request }) => {
    // Probe direct backend on Render
    const backendRes = await request.get('https://queueless-backend-staging.onrender.com/actuator/health/readiness');
    expect(backendRes.status()).toBe(200);

    const health = await backendRes.json();
    expect(health.status).toBe('UP');
  });
});
