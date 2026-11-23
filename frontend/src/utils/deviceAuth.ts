/**
 * QueueLess Cryptographic Device-Bound Authentication Utility
 *
 * Implements Web Crypto API (ECDSA P-256) for hardware/browser-bound device authentication.
 * Stores non-extractable private key securely in client IndexedDB where supported.
 */

const DB_NAME = 'queueless_device_keystore';
const DB_VERSION = 1;
const STORE_NAME = 'device_keys';
const KEY_NAME = 'device_signing_key';
const DRID_STORAGE_KEY = 'queueless_device_registration_id';

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      return reject(new Error('IndexedDB is not supported on this platform'));
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function saveKey(key: CryptoKey): Promise<void> {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite');
    const store = tx.objectStore(STORE_NAME);
    const req = store.put(key, KEY_NAME);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
  });
}

async function loadPrivateKey(): Promise<CryptoKey | null> {
  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);
      const req = store.get(KEY_NAME);
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => reject(req.error);
    });
  } catch {
    return null;
  }
}

export function getOrCreateDeviceRegistrationId(): string {
  let drid = localStorage.getItem(DRID_STORAGE_KEY);
  if (!drid) {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      drid = crypto.randomUUID();
    } else {
      drid = 'dev_' + Math.random().toString(36).substring(2) + Date.now().toString(36);
    }
    localStorage.setItem(DRID_STORAGE_KEY, drid);
  }
  return drid;
}

export function setDeviceRegistrationId(drid: string): void {
  localStorage.setItem(DRID_STORAGE_KEY, drid);
}

export function detectDevicePlatform(): string {
  if (typeof navigator === 'undefined') return 'Web/PWA';
  const ua = navigator.userAgent;
  if (/android/i.test(ua)) return 'Android';
  if (/iPad|iPhone|iPod/.test(ua)) return 'iOS';
  if (/Windows/i.test(ua)) return 'Windows';
  if (/Macintosh|Mac OS X/i.test(ua)) return 'MacOS';
  if (/Linux/i.test(ua)) return 'Linux';
  return 'Web/PWA';
}

export function detectDeviceName(): string {
  if (typeof navigator === 'undefined') return 'Web Browser';
  const ua = navigator.userAgent;
  const platform = detectDevicePlatform();
  let browser = 'Browser';
  if (/chrome|crios/i.test(ua) && !/edge|edg/i.test(ua)) browser = 'Chrome';
  else if (/safari/i.test(ua) && !/chrome/i.test(ua)) browser = 'Safari';
  else if (/firefox|fxios/i.test(ua)) browser = 'Firefox';
  else if (/edg/i.test(ua)) browser = 'Edge';
  return `${browser} on ${platform}`;
}

export async function generateDeviceKeyPair(): Promise<{ publicKeyJwk: string; deviceRegistrationId: string }> {
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    throw new Error('Web Cryptography API is not available on this device');
  }

  // Generate ECDSA P-256 key pair
  const keyPair = await crypto.subtle.generateKey(
    {
      name: 'ECDSA',
      namedCurve: 'P-256',
    },
    false, // Private key is non-extractable where supported
    ['sign', 'verify']
  );

  // Store private key securely in IndexedDB
  await saveKey(keyPair.privateKey);

  // Export public key in standard JWK format
  const jwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey);
  const publicKeyJwk = JSON.stringify(jwk);
  const deviceRegistrationId = getOrCreateDeviceRegistrationId();

  return { publicKeyJwk, deviceRegistrationId };
}

export async function hasLocalDeviceKey(): Promise<boolean> {
  try {
    const key = await loadPrivateKey();
    return !!key;
  } catch {
    return false;
  }
}

export async function clearLocalDeviceKeys(): Promise<void> {
  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);
      const req = store.delete(KEY_NAME);
      req.onsuccess = () => {
        localStorage.removeItem(DRID_STORAGE_KEY);
        resolve();
      };
      req.onerror = () => reject(req.error);
    });
  } catch {
    localStorage.removeItem(DRID_STORAGE_KEY);
  }
}

export async function signDeviceChallenge(
  challengeOrPhone: string,
  phoneOrTimestamp: string | number,
  deviceRegistrationId: string
): Promise<string | null> {
  try {
    const privateKey = await loadPrivateKey();
    if (!privateKey) {
      return null;
    }

    let payload: string;
    if (typeof phoneOrTimestamp === 'number') {
      // Legacy timestamp format: phone:timestamp:deviceRegistrationId
      payload = `${challengeOrPhone}:${phoneOrTimestamp}:${deviceRegistrationId}`;
    } else {
      // Server challenge nonce format: challenge:phone:deviceRegistrationId
      payload = `${challengeOrPhone}:${phoneOrTimestamp}:${deviceRegistrationId}`;
    }

    const encoder = new TextEncoder();
    const data = encoder.encode(payload);

    const signatureBuffer = await crypto.subtle.sign(
      {
        name: 'ECDSA',
        hash: { name: 'SHA-256' },
      },
      privateKey,
      data
    );

    // Convert to Base64 string
    const bytes = new Uint8Array(signatureBuffer);
    let binary = '';
    for (let i = 0; i < bytes.byteLength; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
  } catch (err) {
    console.warn('Device signing unavailable or failed:', err);
    return null;
  }
}
