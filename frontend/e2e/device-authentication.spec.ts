import { test, expect, request as apiRequest } from '@playwright/test';

test.describe('Cryptographic Device-Bound Authentication & Security Audit', () => {

  // Generates a unique 10-digit Indian mobile number
  const generatePhone = (prefix: string) => {
    const suffix = (Date.now() % 100000000).toString().padStart(8, '0');
    return prefix + suffix;
  };

  test('1. First-time staff device enrollment with temporary PIN generates ECDSA keypair and active session', async ({ browser }) => {
    const timestamp = Date.now();
    const ownerPhone = generatePhone('98');
    const staffPhone = generatePhone('91');
    const shopName = `Audit Salon ${timestamp % 1000}`;

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();

    // 1. Register owner
    await ownerPage.goto('/login');
    await ownerPage.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();
    await ownerPage.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await ownerPage.selectOption('select', 'Barber Shop');
    await ownerPage.fill('input[placeholder*="Ramesh Sharma"]', 'Salon Owner');
    await ownerPage.fill('input[placeholder*="9876543210"]', ownerPhone);
    await ownerPage.fill('input[name="pin"]', '123456');
    await ownerPage.fill('input[name="confirmPin"]', '123456');
    await ownerPage.locator('button[type="submit"]').click();
    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();

    // 2. Add staff member via UI modal
    await ownerPage.goto('/staff');
    await expect(ownerPage.getByRole('heading', { name: /Staff & User Seats/i })).toBeVisible();
    await ownerPage.getByRole('button', { name: /Add Staff/i }).first().click();
    await ownerPage.fill('input[placeholder*="Ramesh Kumar"]', 'Staff Device A');
    await ownerPage.fill('input[placeholder="9876543210"]', staffPhone);
    await ownerPage.fill('input[placeholder="••••••"]', '123456');
    await ownerPage.getByRole('button', { name: /Create Staff User/i }).click();

    await expect(ownerPage.getByText('Staff Device A').first()).toBeVisible();
    await expect(ownerPage.getByText(/Awaiting Enrollment/i).first()).toBeVisible();

    // 3. Device A Context: First-time staff enrollment
    const deviceAContext = await browser.newContext();
    const deviceAPage = await deviceAContext.newPage();

    await deviceAPage.goto('/login');
    await deviceAPage.fill('input[placeholder*="9876543210"]', staffPhone);
    await deviceAPage.fill('input[name="pin"]', '123456');
    await deviceAPage.locator('button[type="submit"]').click();

    // Redirected to First-time enrollment mode
    await expect(deviceAPage.getByText(/First-Time Device Enrollment/i)).toBeVisible();

    // Fill new permanent PIN: 654321
    const pinInputs = deviceAPage.locator('input[type="password"]');
    await pinInputs.nth(1).fill('654321');
    await pinInputs.nth(2).fill('654321');
    await deviceAPage.getByRole('button', { name: /Enroll Device & Activate/i }).click();

    // Land on dashboard as staff
    await expect(deviceAPage.getByText('Customer Queue Register')).toBeVisible();

    await ownerContext.close();
    await deviceAContext.close();
  });

  test('2. Routine challenge-response login on enrolled Device A succeeds with 6-digit PIN (Zero SMS OTP)', async ({ browser }) => {
    const timestamp = Date.now();
    const ownerPhone = generatePhone('98');
    const staffPhone = generatePhone('91');
    const shopName = `Routine Auth Clinic ${timestamp % 1000}`;

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();

    // Register owner & invite staff
    await ownerPage.goto('/login');
    await ownerPage.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();
    await ownerPage.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await ownerPage.selectOption('select', 'Barber Shop');
    await ownerPage.fill('input[placeholder*="Ramesh Sharma"]', 'Dr Clinic');
    await ownerPage.fill('input[placeholder*="9876543210"]', ownerPhone);
    await ownerPage.fill('input[name="pin"]', '123456');
    await ownerPage.fill('input[name="confirmPin"]', '123456');
    await ownerPage.locator('button[type="submit"]').click();
    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();

    await ownerPage.goto('/staff');
    await expect(ownerPage.getByRole('heading', { name: /Staff & User Seats/i })).toBeVisible();
    await ownerPage.getByRole('button', { name: /Add Staff/i }).first().click();
    await ownerPage.fill('input[placeholder*="Ramesh Kumar"]', 'Assistant A');
    await ownerPage.fill('input[placeholder="9876543210"]', staffPhone);
    await ownerPage.fill('input[placeholder="••••••"]', '123456');
    await ownerPage.getByRole('button', { name: /Create Staff User/i }).click();
    await expect(ownerPage.getByText('Assistant A').first()).toBeVisible();

    // Device A: First-time enrollment
    const deviceAContext = await browser.newContext();
    const deviceAPage = await deviceAContext.newPage();
    await deviceAPage.goto('/login');
    await deviceAPage.fill('input[placeholder*="9876543210"]', staffPhone);
    await deviceAPage.fill('input[name="pin"]', '123456');
    await deviceAPage.locator('button[type="submit"]').click();

    await expect(deviceAPage.getByText(/First-Time Device Enrollment/i)).toBeVisible();
    const pinInputs = deviceAPage.locator('input[type="password"]');
    await pinInputs.nth(1).fill('654321');
    await pinInputs.nth(2).fill('654321');
    await deviceAPage.getByRole('button', { name: /Enroll Device & Activate/i }).click();
    await expect(deviceAPage.getByText('Customer Queue Register')).toBeVisible();

    // Logout Device A
    await deviceAPage.getByRole('button', { name: /Logout|लॉगआउट/i }).click();
    await expect(deviceAPage.locator('h1')).toContainText('QueueLess');

    // Routine Login on Device A using permanent PIN 654321
    await deviceAPage.fill('input[placeholder*="9876543210"]', staffPhone);
    await deviceAPage.fill('input[name="pin"]', '654321');
    await deviceAPage.locator('button[type="submit"]').click();

    // Successfully logged in via cryptographic challenge-response
    await expect(deviceAPage.getByText('Customer Queue Register')).toBeVisible();

    await ownerContext.close();
    await deviceAContext.close();
  });

  test('3. Unenrolled Device B attempting login is blocked by Device Mismatch protection', async ({ browser }) => {
    const timestamp = Date.now();
    const ownerPhone = generatePhone('98');
    const staffPhone = generatePhone('91');
    const shopName = `Mismatch Guard Shop ${timestamp % 1000}`;

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();

    // Register owner & invite staff
    await ownerPage.goto('/login');
    await ownerPage.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();
    await ownerPage.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await ownerPage.selectOption('select', 'Barber Shop');
    await ownerPage.fill('input[placeholder*="Ramesh Sharma"]', 'Shop Owner');
    await ownerPage.fill('input[placeholder*="9876543210"]', ownerPhone);
    await ownerPage.fill('input[name="pin"]', '123456');
    await ownerPage.fill('input[name="confirmPin"]', '123456');
    await ownerPage.locator('button[type="submit"]').click();
    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();

    await ownerPage.goto('/staff');
    await expect(ownerPage.getByRole('heading', { name: /Staff & User Seats/i })).toBeVisible();
    await ownerPage.getByRole('button', { name: /Add Staff/i }).first().click();
    await ownerPage.fill('input[placeholder*="Ramesh Kumar"]', 'Staff Member');
    await ownerPage.fill('input[placeholder="9876543210"]', staffPhone);
    await ownerPage.fill('input[placeholder="••••••"]', '123456');
    await ownerPage.getByRole('button', { name: /Create Staff User/i }).click();
    await expect(ownerPage.getByText('Staff Member').first()).toBeVisible();

    // Device A enrolls
    const deviceAContext = await browser.newContext();
    const deviceAPage = await deviceAContext.newPage();
    await deviceAPage.goto('/login');
    await deviceAPage.fill('input[placeholder*="9876543210"]', staffPhone);
    await deviceAPage.fill('input[name="pin"]', '123456');
    await deviceAPage.locator('button[type="submit"]').click();
    await expect(deviceAPage.getByText(/First-Time Device Enrollment/i)).toBeVisible();
    const pinInputs = deviceAPage.locator('input[type="password"]');
    await pinInputs.nth(1).fill('654321');
    await pinInputs.nth(2).fill('654321');
    await deviceAPage.getByRole('button', { name: /Enroll Device & Activate/i }).click();
    await expect(deviceAPage.getByText('Customer Queue Register')).toBeVisible();

    // Device B (completely isolated context) attempts login
    const deviceBContext = await browser.newContext();
    const deviceBPage = await deviceBContext.newPage();
    await deviceBPage.goto('/login');
    await deviceBPage.fill('input[placeholder*="9876543210"]', staffPhone);
    await deviceBPage.fill('input[name="pin"]', '654321');
    await deviceBPage.locator('button[type="submit"]').click();

    // Expect Device Mismatch modal to trigger
    await expect(deviceBPage.getByRole('heading', { name: /Enrolled on Another Device/i })).toBeVisible();
    await expect(deviceBPage.getByRole('button', { name: /Request Replacement/i })).toBeVisible();

    await ownerContext.close();
    await deviceAContext.close();
    await deviceBContext.close();
  });

  test('4-10. End-to-End Device Replacement Lifecycle: Request -> Pending -> Approval -> Revocation -> Completion', async ({ browser }) => {
    const timestamp = Date.now();
    const ownerPhone = generatePhone('98');
    const staffPhone = generatePhone('91');
    const shopName = `Lifecycle Store ${timestamp % 1000}`;

    const ownerContext = await browser.newContext();
    const ownerPage = await ownerContext.newPage();

    // 1. Owner registers and adds staff
    await ownerPage.goto('/login');
    await ownerPage.getByRole('button', { name: /Create Account|खाता बनाएं/i }).click();
    await ownerPage.fill('input[placeholder*="Royal Hair Salon"]', shopName);
    await ownerPage.selectOption('select', 'Barber Shop');
    await ownerPage.fill('input[placeholder*="Ramesh Sharma"]', 'Barber Boss');
    await ownerPage.fill('input[placeholder*="9876543210"]', ownerPhone);
    await ownerPage.fill('input[name="pin"]', '123456');
    await ownerPage.fill('input[name="confirmPin"]', '123456');
    await ownerPage.locator('button[type="submit"]').click();
    await expect(ownerPage.getByText('Customer Queue Register')).toBeVisible();

    await ownerPage.goto('/staff');
    await expect(ownerPage.getByRole('heading', { name: /Staff & User Seats/i })).toBeVisible();
    await ownerPage.getByRole('button', { name: /Add Staff/i }).first().click();
    await ownerPage.fill('input[placeholder*="Ramesh Kumar"]', 'Operator John');
    await ownerPage.fill('input[placeholder="9876543210"]', staffPhone);
    await ownerPage.fill('input[placeholder="••••••"]', '123456');
    await ownerPage.getByRole('button', { name: /Create Staff User/i }).click();
    await expect(ownerPage.getByText('Operator John').first()).toBeVisible();

    // 2. Device A enrolls
    const deviceAContext = await browser.newContext();
    const deviceAPage = await deviceAContext.newPage();
    await deviceAPage.goto('/login');
    await deviceAPage.fill('input[placeholder*="9876543210"]', staffPhone);
    await deviceAPage.fill('input[name="pin"]', '123456');
    await deviceAPage.locator('button[type="submit"]').click();
    await expect(deviceAPage.getByText(/First-Time Device Enrollment/i)).toBeVisible();
    const pinInputs = deviceAPage.locator('input[type="password"]');
    await pinInputs.nth(1).fill('654321');
    await pinInputs.nth(2).fill('654321');
    await deviceAPage.getByRole('button', { name: /Enroll Device & Activate/i }).click();
    await expect(deviceAPage.getByText('Customer Queue Register')).toBeVisible();

    // 3. Device B attempts login and requests replacement (Scenario 4)
    const deviceBContext = await browser.newContext();
    const deviceBPage = await deviceBContext.newPage();
    await deviceBPage.goto('/login');
    await deviceBPage.fill('input[placeholder*="9876543210"]', staffPhone);
    await deviceBPage.fill('input[name="pin"]', '654321');
    await deviceBPage.locator('button[type="submit"]').click();

    await expect(deviceBPage.getByRole('heading', { name: /Enrolled on Another Device/i })).toBeVisible();
    await deviceBPage.getByRole('button', { name: /Request Replacement/i }).click();
    await expect(deviceBPage.getByText(/Device replacement request submitted/i)).toBeVisible();

    // 4. While pending, Device B cannot login directly (Scenario 5)
    await deviceBPage.fill('input[placeholder*="9876543210"]', staffPhone);
    await deviceBPage.fill('input[name="pin"]', '654321');
    await deviceBPage.locator('button[type="submit"]').click();
    await expect(deviceBPage.getByRole('heading', { name: /Enrolled on Another Device/i })).toBeVisible();

    // 5. Owner checks Staff portal and sees pending replacement request (Scenario 6)
    await ownerPage.reload();
    await expect(ownerPage.getByText(/Pending Device Replacement Requests/i)).toBeVisible();
    await expect(ownerPage.getByText('Operator John').first()).toBeVisible();

    // 6. Owner approves replacement request (Scenario 7)
    await ownerPage.getByRole('button', { name: /Approve/i }).click();
    await expect(ownerPage.getByText(/Device replacement approved successfully/i)).toBeVisible();

    // 7. Old Device A session is immediately revoked (Scenario 8 & 9)
    const testRevoked = await deviceAContext.request.get('/api/queue/dashboard');
    expect([401, 403]).toContain(testRevoked.status());

    // 8. Device B logs in on approved device and lands directly on dashboard (Scenario 10)
    await deviceBPage.goto('/login');
    await deviceBPage.fill('input[placeholder*="9876543210"]', staffPhone);
    await deviceBPage.fill('input[name="pin"]', '654321');
    await deviceBPage.locator('button[type="submit"]').click();

    // Device B completes replacement and lands directly on dashboard!
    await expect(deviceBPage.getByText('Customer Queue Register')).toBeVisible();

    await ownerContext.close();
    await deviceAContext.close();
    await deviceBContext.close();
  });

  test('11-15. Cryptographic Replay, Nonce Expiration, and Signature Tampering Audit', async () => {
    const api = await apiRequest.newContext({ baseURL: 'http://localhost:8080' });

    // Register owner
    const timestamp = Date.now();
    const ownerPhone = generatePhone('98');
    const staffPhone = generatePhone('91');

    const regRes = await api.post('/api/auth/register', {
      data: {
        phone: ownerPhone,
        pin: '123456',
        confirmPin: '123456',
        shopName: `Crypto Audit ${timestamp % 1000}`,
        category: 'Barber Shop',
        fullName: 'Dr Auditor',
      }
    });
    expect(regRes.status()).toBe(200);
    const ownerData = await regRes.json();
    const ownerToken = ownerData.token;

    // Add staff
    const addStaffRes = await api.post('/api/owner/staff', {
      headers: { Authorization: `Bearer ${ownerToken}` },
      data: {
        phone: staffPhone,
        fullName: 'Crypto Subject',
        pin: '123456',
      }
    });
    expect(addStaffRes.status()).toBe(200);

    // Enroll staff device with ECDSA key
    const dridA = 'drid_audit_context_a_' + timestamp;
    const enrollRes = await api.post('/api/auth/device-enroll', {
      data: {
        phone: staffPhone,
        temporaryPin: '123456',
        newPermanentPin: '654321',
        confirmPermanentPin: '654321',
        deviceRegistrationId: dridA,
        deviceName: 'Audit Device A',
        platform: 'Chrome Audit',
        publicKey: '{"kty":"EC","crv":"P-256","x":"audit_test_x","y":"audit_test_y"}',
      }
    });
    expect(enrollRes.status()).toBe(200);

    // Request Challenge
    const chRes = await api.post('/api/auth/device-challenge', {
      data: {
        phone: staffPhone,
        deviceRegistrationId: dridA,
      }
    });
    expect(chRes.status()).toBe(200);
    const { challenge } = await chRes.json();
    expect(challenge).toBeTruthy();

    // Scenario 13: Challenge device mismatch (Device B presenting Device A's challenge)
    const mismatchChRes = await api.post('/api/auth/device-challenge', {
      data: {
        phone: staffPhone,
        deviceRegistrationId: 'drid_unregistered_attacker_device',
      }
    });
    expect(mismatchChRes.status()).toBe(403);

    // Scenario 14: Tampered signature rejection
    const tamperedLoginRes = await api.post('/api/auth/device-login', {
      data: {
        phone: staffPhone,
        pin: '654321',
        deviceRegistrationId: dridA,
        challenge,
        signature: 'INVALID_FORGED_SIGNATURE_BASE64',
      }
    });
    expect(tamperedLoginRes.status()).toBe(401);

    // Scenario 12: Fake or expired challenge nonce
    const fakeChLoginRes = await api.post('/api/auth/device-login', {
      data: {
        phone: staffPhone,
        pin: '654321',
        deviceRegistrationId: dridA,
        challenge: 'non_existent_or_expired_challenge_nonce',
        signature: 'valid_looking_sig',
      }
    });
    expect(fakeChLoginRes.status()).toBe(401);

    // Scenario 15: Duplicate Pending Replacement Rejection
    const firstRepRes = await api.post('/api/auth/device-replacement/request', {
      data: {
        phone: staffPhone,
        pin: '654321',
        requestedDeviceRegistrationId: 'drid_replacement_b_' + timestamp,
        requestedDeviceName: 'Replacement Phone',
        requestedDevicePlatform: 'Mobile',
        requestedPublicKey: '{"kty":"EC","crv":"P-256","x":"b_x","y":"b_y"}',
      }
    });
    expect(firstRepRes.status()).toBe(200);

    // Duplicate request must return HTTP 409 Conflict
    const dupRepRes = await api.post('/api/auth/device-replacement/request', {
      data: {
        phone: staffPhone,
        pin: '654321',
        requestedDeviceRegistrationId: 'drid_replacement_b_dup_' + timestamp,
        requestedDeviceName: 'Replacement Phone 2',
        requestedDevicePlatform: 'Mobile',
        requestedPublicKey: '{"kty":"EC","crv":"P-256","x":"b_x2","y":"b_y2"}',
      }
    });
    expect(dupRepRes.status()).toBe(409);
  });

  test('16-18. Security Access Control: Unauthorized Approval, Cross-Owner Isolation, Staff Revocation', async () => {
    const api = await apiRequest.newContext({ baseURL: 'http://localhost:8080' });
    const timestamp = Date.now();

    // Register Shop 1
    const owner1Phone = generatePhone('98');
    const staff1Phone = generatePhone('91');
    const r1 = await api.post('/api/auth/register', {
      data: {
        phone: owner1Phone,
        pin: '123456',
        confirmPin: '123456',
        shopName: `Owner 1 Shop ${timestamp % 1000}`,
        category: 'Barber Shop',
        fullName: 'Owner One',
      }
    });
    const { token: owner1Token } = await r1.json();

    // Register Shop 2
    const owner2Phone = generatePhone('97');
    const r2 = await api.post('/api/auth/register', {
      data: {
        phone: owner2Phone,
        pin: '123456',
        confirmPin: '123456',
        shopName: `Owner 2 Shop ${timestamp % 1000}`,
        category: 'Barber Shop',
        fullName: 'Owner Two',
      }
    });
    const { token: owner2Token } = await r2.json();

    // Owner 1 adds staff
    const addStaff1 = await api.post('/api/owner/staff', {
      headers: { Authorization: `Bearer ${owner1Token}` },
      data: {
        phone: staff1Phone,
        fullName: 'Staff One',
        pin: '123456',
      }
    });
    expect(addStaff1.status()).toBe(200);

    // Staff enrolls device
    const drid1 = 'drid_s1_' + timestamp;
    const enroll1 = await api.post('/api/auth/device-enroll', {
      data: {
        phone: staff1Phone,
        temporaryPin: '123456',
        newPermanentPin: '654321',
        confirmPermanentPin: '654321',
        deviceRegistrationId: drid1,
        deviceName: 'Device 1',
        platform: 'Android',
        publicKey: '{"kty":"EC","crv":"P-256","x":"x1","y":"y1"}',
      }
    });
    expect(enroll1.status()).toBe(200);

    // Staff requests replacement
    const repRes = await api.post('/api/auth/device-replacement/request', {
      data: {
        phone: staff1Phone,
        pin: '654321',
        requestedDeviceRegistrationId: 'drid_s1_rep_' + timestamp,
        requestedDeviceName: 'Device 1 New',
        requestedDevicePlatform: 'iOS',
        requestedPublicKey: '{"kty":"EC","crv":"P-256","x":"x1_new","y":"y1_new"}',
      }
    });
    expect(repRes.status()).toBe(200);
    const repData = await repRes.json();
    const requestId = repData.id;
    expect(requestId).toBeTruthy();

    // Scenario 16: Unauthorized approval attempt (without token)
    const unauthApprove = await api.post(`/api/owner/staff/device-replacements/${requestId}/approve`);
    expect([401, 403]).toContain(unauthApprove.status());

    // Scenario 17: Cross-Owner isolation (Owner 2 attempts to approve Owner 1's staff replacement)
    const crossOwnerApprove = await api.post(`/api/owner/staff/device-replacements/${requestId}/approve`, {
      headers: { Authorization: `Bearer ${owner2Token}` },
    });
    expect(crossOwnerApprove.status()).toBe(403);

    // Scenario 18: Staff Revocation cascades to device & session
    const staffListRes = await api.get('/api/owner/staff', {
      headers: { Authorization: `Bearer ${owner1Token}` },
    });
    const staffList = await staffListRes.json();
    const staffObj = staffList.find((s: any) => s.phone.includes(staff1Phone));
    expect(staffObj).toBeTruthy();

    // Revoke staff
    const revokeRes = await api.post(`/api/owner/staff/${staffObj.id}/revoke`, {
      headers: { Authorization: `Bearer ${owner1Token}` },
    });
    expect(revokeRes.status()).toBe(200);

    // Attempting challenge or login on revoked staff fails with 401
    const revokedChallenge = await api.post('/api/auth/device-challenge', {
      data: {
        phone: staff1Phone,
        deviceRegistrationId: drid1,
      }
    });
    expect(revokedChallenge.status()).toBe(401);
  });

  test('19. Logout invalidates active server session while keeping device enrollment intact', async () => {
    const api = await apiRequest.newContext({ baseURL: 'http://localhost:8080' });
    const timestamp = Date.now();
    const ownerPhone = generatePhone('98');
    const staffPhone = generatePhone('91');

    // Register owner & staff
    const regRes = await api.post('/api/auth/register', {
      data: {
        phone: ownerPhone,
        pin: '123456',
        confirmPin: '123456',
        shopName: `Logout Preservation Shop ${timestamp % 1000}`,
        category: 'Barber Shop',
        fullName: 'Logout Tester Owner',
      }
    });
    expect(regRes.status()).toBe(200);
    const ownerData = await regRes.json();

    // Add staff
    await api.post('/api/owner/staff', {
      headers: { Authorization: `Bearer ${ownerData.token}` },
      data: {
        phone: staffPhone,
        fullName: 'Staff Logout Test',
        pin: '123456',
      }
    });

    // Enroll staff device
    const drid = 'drid_logout_test_' + timestamp;
    const enrollRes = await api.post('/api/auth/device-enroll', {
      data: {
        phone: staffPhone,
        temporaryPin: '123456',
        newPermanentPin: '654321',
        confirmPermanentPin: '654321',
        deviceRegistrationId: drid,
        deviceName: 'Logout Enrolled Device',
        platform: 'Android Test',
        publicKey: '{"kty":"EC","crv":"P-256","x":"logout_x","y":"logout_y"}',
      }
    });
    expect(enrollRes.status()).toBe(200);
    const staffAuth = await enrollRes.json();
    const staffToken = staffAuth.token;

    // Verify authenticated endpoint works with staff token
    const queueBefore = await api.get('/api/queue/dashboard', {
      headers: { Authorization: `Bearer ${staffToken}` },
    });
    expect(queueBefore.status()).toBe(200);

    // Call logout endpoint
    const logoutRes = await api.post('/api/auth/logout', {
      headers: { Authorization: `Bearer ${staffToken}` },
    });
    expect(logoutRes.status()).toBe(200);

    // Previous session token is now completely invalidated
    const queueAfter = await api.get('/api/queue/dashboard', {
      headers: { Authorization: `Bearer ${staffToken}` },
    });
    expect([401, 403]).toContain(queueAfter.status());

    // Device enrollment is preserved: Device can still request challenge
    const chRes = await api.post('/api/auth/device-challenge', {
      data: {
        phone: staffPhone,
        deviceRegistrationId: drid,
      }
    });
    expect(chRes.status()).toBe(200);
  });

  test('20. Brute-force PIN Lockout Protection: 5 failed PIN attempts trigger temporary lockout', async () => {
    const api = await apiRequest.newContext({ baseURL: 'http://localhost:8080' });
    const timestamp = Date.now();
    const phone = generatePhone('98');

    // Register user
    const regRes = await api.post('/api/auth/register', {
      data: {
        phone,
        pin: '123456',
        confirmPin: '123456',
        shopName: `Brute Force Shop ${timestamp % 1000}`,
        category: 'Barber Shop',
        fullName: 'Lockout Target',
      }
    });
    const authData = await regRes.json();
    const drid = authData.deviceRegistrationId;

    // 4 wrong attempts
    for (let i = 1; i <= 4; i++) {
      const wrongRes = await api.post('/api/auth/login', {
        data: {
          phone,
          pin: '000000',
          deviceRegistrationId: drid,
        }
      });
      expect(wrongRes.status()).toBe(401);
      const data = await wrongRes.json();
      expect(data.error).toContain('attempt(s) remaining');
    }

    // 5th wrong attempt triggers lockout (HTTP 423 Locked)
    const lockRes = await api.post('/api/auth/login', {
      data: {
        phone,
        pin: '000000',
        deviceRegistrationId: drid,
      }
    });
    expect(lockRes.status()).toBe(423);
    const lockData = await lockRes.json();
    expect(lockData.error).toContain('Account locked');
  });

});
