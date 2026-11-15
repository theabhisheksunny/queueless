import { Page } from '@playwright/test';

/**
 * In automated end-to-end tests, retrieves the dynamic OTP dispatched to the test SMS provider
 * and enters it into the OTP input field, simulating a user reading SMS on their mobile.
 */
export async function fillDispatchedOtp(page: Page, phone: string): Promise<string> {
  const res = await page.request.get(`/api/test/last-otp?phone=${encodeURIComponent(phone)}`);
  if (!res.ok()) {
    throw new Error(`Failed to retrieve dispatched OTP for ${phone}: ${res.status()}`);
  }
  const data = await res.json();
  const otp = data.otp;
  await page.fill('input[placeholder*="Enter 6-digit OTP"]', otp);
  return otp;
}
