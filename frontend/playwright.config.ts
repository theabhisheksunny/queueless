import { defineConfig, devices } from '@playwright/test';

// Explicit environment selection:
// - Default to local development server (http://localhost:8080) to prevent accidental execution against staging.
// - To target staging: set TEST_ENV=staging or provide PLAYWRIGHT_BASE_URL.
const testEnv = process.env.TEST_ENV || 'local';
const defaultBaseUrl = testEnv === 'staging'
  ? 'https://queueless-staging.theabhisheksunny.workers.dev'
  : 'http://localhost:8080';
const baseURL = process.env.PLAYWRIGHT_BASE_URL || defaultBaseUrl;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  timeout: 60000,
  expect: {
    timeout: 15000,
  },
  reporter: 'list',
  use: {
    baseURL,
    trace: 'on-first-retry',
    headless: true,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'edge',
      use: { ...devices['Desktop Edge'], channel: 'msedge' },
    },
    {
      name: 'mobile-chrome',
      use: { ...devices['Pixel 7'] },
    },
  ],
});
