/**
 * Cloudflare Worker for QueueLess
 * - Serves frontend static assets with SPA fallback for React Router routes.
 * - Proxies /api/* requests to BACKEND_API_URL (if configured).
 * - Ensures correct caching and headers for service worker and web manifest.
 */
interface Env {
  ASSETS: {
    fetch: (request: Request) => Promise<Response>;
  };
  BACKEND_API_URL?: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // 1. Proxy API requests to Spring Boot backend if BACKEND_API_URL is configured
    if (url.pathname.startsWith('/api/') && env.BACKEND_API_URL) {
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
        responseHeaders.set('Access-Control-Allow-Origin', url.origin);
        responseHeaders.set('Access-Control-Allow-Credentials', 'true');

        return new Response(backendResponse.body, {
          status: backendResponse.status,
          statusText: backendResponse.statusText,
          headers: responseHeaders,
        });
      } catch (err: any) {
        return new Response(
          JSON.stringify({
            error: 'Backend gateway connection failed: ' + (err.message || 'Service unavailable'),
            status: 502,
          }),
          {
            status: 502,
            headers: {
              'Content-Type': 'application/json',
              'Access-Control-Allow-Origin': url.origin,
            },
          }
        );
      }
    }

    // 2. Serve static assets with single-page-application fallback
    const response = await env.ASSETS.fetch(request);

    // Custom headers for service worker
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

    return response;
  },
};
