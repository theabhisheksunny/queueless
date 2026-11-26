/**
 * Cloudflare Worker for QueueLess
 * - Serves frontend static assets with SPA fallback for React Router routes.
 * - Proxies /api/* requests to BACKEND_API_URL (Cloud Run / Render Spring Boot backend).
 * - Preserves unbuffered real-time SSE event streams (text/event-stream).
 * - Prevents caching of private API responses and ensures no open-proxy abuse.
 * - Enforces strict CORS origin validation, restricting cross-origin access to trusted origins.
 * - Ensures correct caching and headers for service worker and web manifest.
 * - Strips all upstream cloud provider & infrastructure disclosure headers.
 * - Enforces Referrer-Policy: no-referrer on all responses to prevent credential/ticket leakage.
 */
interface Env {
  ASSETS: {
    fetch: (request: Request) => Promise<Response>;
  };
  BACKEND_API_URL?: string;
  ALLOWED_ORIGINS?: string;
}

const TRUSTED_ORIGIN_PATTERNS: RegExp[] = [
  /^https:\/\/queueless\.theabhisheksunny\.workers\.dev$/,
  /^https:\/\/queueless-staging\.theabhisheksunny\.workers\.dev$/,
  /^http:\/\/localhost(:\d+)?$/,
  /^http:\/\/127\.0\.0\.1(:\d+)?$/,
];

export const UPSTREAM_DISCLOSURE_HEADERS: string[] = [
  'server',
  'via',
  'x-powered-by',
  'x-cloud-trace-context',
  'x-render-origin-server',
  'x-render-routing',
  'cf-ray',
  'cf-cache-status',
];

const SENSITIVE_QUERY_PARAM_REGEX = /(?<=[?&])(token|ticket|admin[-_]?key|password|pin|secret|auth|access[-_]?token)=([^&#\s]+)/gi;

/**
 * Sanitizes URLs for logging or diagnostic output by redacting sensitive query parameters.
 */
export function sanitizeUrlForLogging(rawUrl: string): string {
  if (!rawUrl) return rawUrl;
  return rawUrl.replace(SENSITIVE_QUERY_PARAM_REGEX, '$1=[REDACTED]');
}

export function isAllowedOrigin(origin: string | null, workerOrigin: string, configuredOrigins?: string): boolean {
  if (!origin) return true; // Same-origin request / non-CORS GET
  if (origin === workerOrigin) return true; // Direct same-origin request

  if (configuredOrigins) {
    const list = configuredOrigins.split(',').map((o) => o.trim());
    if (list.includes(origin)) return true;
  }

  return TRUSTED_ORIGIN_PATTERNS.some((pattern) => pattern.test(origin));
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // 1. Strict API Proxying: Forward /api/* requests to Spring Boot backend
    // Prevents open proxying by only routing paths starting with /api/
    if (url.pathname.startsWith('/api/')) {
      if (!env.BACKEND_API_URL || !env.BACKEND_API_URL.trim()) {
        return new Response(
          JSON.stringify({
            error: 'Backend API gateway unconfigured: BACKEND_API_URL environment variable is missing on edge Worker.',
            status: 503,
          }),
          {
            status: 503,
            headers: {
              'Content-Type': 'application/json',
              'Cache-Control': 'no-store, no-cache, must-revalidate',
              'Referrer-Policy': 'no-referrer',
            },
          }
        );
      }

      const origin = request.headers.get('Origin');
      const originAllowed = isAllowedOrigin(origin, url.origin, env.ALLOWED_ORIGINS);

      // Handle CORS Preflight (OPTIONS)
      if (request.method === 'OPTIONS') {
        if (!originAllowed) {
          return new Response(JSON.stringify({ error: 'Forbidden: CORS origin not allowed' }), {
            status: 403,
            headers: {
              'Content-Type': 'application/json',
              'Referrer-Policy': 'no-referrer',
            },
          });
        }
        return new Response(null, {
          status: 204,
          headers: {
            'Access-Control-Allow-Origin': origin || url.origin,
            'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, PATCH, OPTIONS',
            'Access-Control-Allow-Headers':
              request.headers.get('Access-Control-Request-Headers') ||
              'Authorization, Content-Type, X-Device-Id, X-Idempotency-Key, X-Shop-Id, X-SSE-Ticket',
            'Access-Control-Allow-Credentials': 'true',
            'Access-Control-Max-Age': '86400',
            'Vary': 'Origin',
            'Referrer-Policy': 'no-referrer',
          },
        });
      }

      // If cross-origin request from an untrusted origin, block with 403 Forbidden
      if (origin && !originAllowed) {
        return new Response(JSON.stringify({ error: 'Forbidden: CORS origin not allowed' }), {
          status: 403,
          headers: {
            'Content-Type': 'application/json',
            'Referrer-Policy': 'no-referrer',
          },
        });
      }

      const backendTarget = env.BACKEND_API_URL.replace(/\/+$/, '');
      const backendUrl = `${backendTarget}${url.pathname}${url.search}`;

      const proxyHeaders = new Headers(request.headers);
      proxyHeaders.set('X-Forwarded-Host', url.host);
      proxyHeaders.set('X-Forwarded-Proto', url.protocol.replace(':', ''));

      try {
        const backendResponse = await fetch(backendUrl, {
          method: request.method,
          headers: proxyHeaders,
          body: ['GET', 'HEAD'].includes(request.method) ? undefined : request.body,
          redirect: 'manual',
        });

        const responseHeaders = new Headers(backendResponse.headers);

        // Strip upstream infrastructure and cloud provider disclosure headers
        for (const headerName of UPSTREAM_DISCLOSURE_HEADERS) {
          responseHeaders.delete(headerName);
        }

        // Privacy header: Prevent sensitive URL query parameters (e.g. ticket=...) from leaking in Referer headers
        responseHeaders.set('Referrer-Policy', 'no-referrer');

        if (originAllowed) {
          responseHeaders.set('Access-Control-Allow-Origin', origin || url.origin);
          responseHeaders.set('Access-Control-Allow-Credentials', 'true');
          responseHeaders.set('Vary', 'Origin');
        }

        const contentType = backendResponse.headers.get('content-type') || '';

        // If upstream failed with an HTML or non-JSON error page on an API route, sanitize to clean JSON
        if (backendResponse.status >= 500 && (contentType.includes('text/html') || !contentType.includes('application/json'))) {
          responseHeaders.set('Content-Type', 'application/json');
          responseHeaders.set('Cache-Control', 'no-store, no-cache, must-revalidate');
          return new Response(
            JSON.stringify({
              error: 'Upstream service temporarily unavailable. Please retry shortly.',
              status: backendResponse.status,
            }),
            {
              status: backendResponse.status,
              headers: responseHeaders,
            }
          );
        }

        // Realtime Server-Sent Events (SSE) handling
        if (contentType.includes('text/event-stream')) {
          // Disable buffering in Cloudflare edge and client proxies
          responseHeaders.set('Cache-Control', 'no-cache, no-transform');
          responseHeaders.set('X-Accel-Buffering', 'no');
          responseHeaders.set('Content-Type', 'text/event-stream; charset=utf-8');
        } else {
          // Enforce zero caching on all private transactional API responses
          responseHeaders.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
          responseHeaders.set('Pragma', 'no-cache');
        }

        // Return streaming body directly without buffering
        return new Response(backendResponse.body, {
          status: backendResponse.status,
          statusText: backendResponse.statusText,
          headers: responseHeaders,
        });
      } catch (err: any) {
        console.error('Edge Worker gateway failure:', err?.name || 'GatewayError');
        const errorHeaders = new Headers({
          'Content-Type': 'application/json',
          'Cache-Control': 'no-store, no-cache, must-revalidate',
          'Referrer-Policy': 'no-referrer',
        });
        if (originAllowed) {
          errorHeaders.set('Access-Control-Allow-Origin', origin || url.origin);
          errorHeaders.set('Access-Control-Allow-Credentials', 'true');
          errorHeaders.set('Vary', 'Origin');
        }
        return new Response(
          JSON.stringify({
            error: 'Bad Gateway: Upstream service temporarily unavailable.',
            status: 502,
          }),
          {
            status: 502,
            headers: errorHeaders,
          }
        );
      }
    }

    // 2. Serve static assets with single-page-application fallback
    const response = await env.ASSETS.fetch(request);
    const assetHeaders = new Headers(response.headers);
    assetHeaders.set('Referrer-Policy', 'no-referrer');

    // Apply specific security and caching headers for PWA assets
    if (url.pathname === '/sw.js') {
      assetHeaders.set('Cache-Control', 'no-cache, no-store, must-revalidate');
      assetHeaders.set('Service-Worker-Allowed', '/');
      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers: assetHeaders,
      });
    }

    if (url.pathname === '/manifest.webmanifest') {
      assetHeaders.set('Content-Type', 'application/manifest+json; charset=utf-8');
      assetHeaders.set('Cache-Control', 'public, max-age=86400');
      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers: assetHeaders,
      });
    }

    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers: assetHeaders,
    });
  },
};
