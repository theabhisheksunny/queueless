/**
 * Cloudflare Worker for QueueLess
 * - Serves frontend static assets with SPA fallback for React Router routes.
 * - Proxies /api/* requests to BACKEND_API_URL (Cloud Run / Render Spring Boot backend).
 * - Preserves unbuffered real-time SSE event streams (text/event-stream).
 * - Prevents caching of private API responses and ensures no open-proxy abuse.
 * - Enforces strict CORS origin validation, restricting cross-origin access to trusted origins.
 * - Ensures correct caching and headers for service worker and web manifest.
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
    if (url.pathname.startsWith('/api/') && env.BACKEND_API_URL) {
      const origin = request.headers.get('Origin');
      const originAllowed = isAllowedOrigin(origin, url.origin, env.ALLOWED_ORIGINS);

      // Handle CORS Preflight (OPTIONS)
      if (request.method === 'OPTIONS') {
        if (!originAllowed) {
          return new Response(JSON.stringify({ error: 'Forbidden: CORS origin not allowed' }), {
            status: 403,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        return new Response(null, {
          status: 204,
          headers: {
            'Access-Control-Allow-Origin': origin || url.origin,
            'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, PATCH, OPTIONS',
            'Access-Control-Allow-Headers':
              request.headers.get('Access-Control-Request-Headers') ||
              'Authorization, Content-Type, X-Device-Id, X-Idempotency-Key',
            'Access-Control-Allow-Credentials': 'true',
            'Access-Control-Max-Age': '86400',
            'Vary': 'Origin',
          },
        });
      }

      // If cross-origin request from an untrusted origin, block with 403 Forbidden
      if (origin && !originAllowed) {
        return new Response(JSON.stringify({ error: 'Forbidden: CORS origin not allowed' }), {
          status: 403,
          headers: {
            'Content-Type': 'application/json',
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
        if (originAllowed) {
          responseHeaders.set('Access-Control-Allow-Origin', origin || url.origin);
          responseHeaders.set('Access-Control-Allow-Credentials', 'true');
          responseHeaders.set('Vary', 'Origin');
        }

        const contentType = backendResponse.headers.get('content-type') || '';

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
        const errorHeaders = new Headers({
          'Content-Type': 'application/json',
        });
        if (originAllowed) {
          errorHeaders.set('Access-Control-Allow-Origin', origin || url.origin);
          errorHeaders.set('Access-Control-Allow-Credentials', 'true');
          errorHeaders.set('Vary', 'Origin');
        }
        return new Response(
          JSON.stringify({
            error: 'Backend gateway connection failed: ' + (err.message || 'Service unavailable'),
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

    // Apply specific security and caching headers for PWA assets
    if (url.pathname === '/sw.js') {
      const swHeaders = new Headers(response.headers);
      swHeaders.set('Cache-Control', 'no-cache, no-store, must-revalidate');
      swHeaders.set('Service-Worker-Allowed', '/');
      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers: swHeaders,
      });
    }

    if (url.pathname === '/manifest.webmanifest') {
      const manifestHeaders = new Headers(response.headers);
      manifestHeaders.set('Content-Type', 'application/manifest+json; charset=utf-8');
      manifestHeaders.set('Cache-Control', 'public, max-age=86400');
      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers: manifestHeaders,
      });
    }

    return response;
  },
};
