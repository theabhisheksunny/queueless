/**
 * URL security utility to prevent Open Redirect vulnerabilities,
 * protocol-relative attacks, backslash trickery, and redirect loops.
 */

/**
 * Sanitizes a target returnUrl to ensure it is strictly a safe, local, relative internal path.
 *
 * Rules:
 * 1. Must start with a single '/'
 * 2. Must not start with '//' (protocol-relative redirect to external domain)
 * 3. Must not start with '/\\' or contain backslashes '\\' (browser path normalization tricks)
 * 4. Must not contain control characters (\r, \n, \t, null bytes)
 * 5. Must not use javascript:, data:, vbscript: or other URI schemes
 * 6. Must not point to /login itself (to prevent login loops)
 */
export function sanitizeReturnUrl(url: string | null | undefined, fallback: string = '/'): string {
  if (!url) return fallback;

  let trimmed = url.trim();
  if (!trimmed) return fallback;

  // Reject protocol-relative or backslash prefixes immediately
  if (trimmed.startsWith('//') || trimmed.startsWith('/\\') || trimmed.startsWith('\\')) {
    return fallback;
  }

  // Must begin with a single '/'
  if (!trimmed.startsWith('/')) {
    return fallback;
  }

  // Reject any backslashes anywhere in the URL
  if (trimmed.includes('\\')) {
    return fallback;
  }

  // Reject control characters
  if (/[\r\n\t\0]/.test(trimmed)) {
    return fallback;
  }

  // Check decoded URL as well to prevent double-encoding evasion
  try {
    const decoded = decodeURIComponent(trimmed);
    if (decoded.startsWith('//') || decoded.startsWith('/\\') || decoded.includes('\\') || /[\r\n\t\0]/.test(decoded)) {
      return fallback;
    }
    // Block scheme injection inside path
    if (/^\/+(javascript|data|vbscript):/i.test(decoded)) {
      return fallback;
    }
  } catch {
    // Malformed URI encoding
    return fallback;
  }

  // Prevent redirecting back to the login page itself
  const pathWithoutQuery = trimmed.split('?')[0];
  if (pathWithoutQuery === '/login') {
    return fallback;
  }

  return trimmed;
}

/**
 * Checks whether a given string is an external or dangerous URL.
 */
export function isExternalUrl(url: string): boolean {
  if (!url) return false;
  const trimmed = url.trim();
  return (
    trimmed.startsWith('http://') ||
    trimmed.startsWith('https://') ||
    trimmed.startsWith('//') ||
    trimmed.startsWith('\\\\') ||
    /^(javascript|data|vbscript):/i.test(trimmed)
  );
}

/**
 * Constructs a safe redirect parameter for the login route.
 */
export function buildLoginRedirectUrl(currentPath: string, isSessionExpired: boolean = false): string {
  const safePath = sanitizeReturnUrl(currentPath, '/');
  const params = new URLSearchParams();

  if (safePath !== '/') {
    params.set('redirect', safePath);
  }
  if (isSessionExpired) {
    params.set('session_expired', 'true');
  }

  const query = params.toString();
  return query ? `/login?${query}` : '/login';
}
