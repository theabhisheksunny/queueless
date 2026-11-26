import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import worker, { UPSTREAM_DISCLOSURE_HEADERS, sanitizeUrlForLogging } from './worker.ts';

describe('Cloudflare Worker P0 Security — Upstream Infrastructure Sanitization', () => {
  const workerOrigin = 'https://queueless.theabhisheksunny.workers.dev';
  const backendTarget = 'https://queueless-backend-1043223278240.asia-south1.run.app';

  it('UPSTREAM_DISCLOSURE_HEADERS includes all required infrastructure headers', () => {
    assert.ok(UPSTREAM_DISCLOSURE_HEADERS.includes('server'));
    assert.ok(UPSTREAM_DISCLOSURE_HEADERS.includes('via'));
    assert.ok(UPSTREAM_DISCLOSURE_HEADERS.includes('x-powered-by'));
    assert.ok(UPSTREAM_DISCLOSURE_HEADERS.includes('x-cloud-trace-context'));
    assert.ok(UPSTREAM_DISCLOSURE_HEADERS.includes('x-render-origin-server'));
  });

  it('strips all upstream provider disclosure headers from successful API responses', async () => {
    const originalFetch = globalThis.fetch;
    try {
      // Mock upstream returning cloud provider disclosure headers
      globalThis.fetch = async () => {
        const headers = new Headers({
          'Content-Type': 'application/json',
          'Server': 'Google Frontend',
          'Via': '1.1 google',
          'X-Powered-By': 'Express/CloudRun',
          'X-Cloud-Trace-Context': '105445aa7843bc8bf206b120001000/1;o=1',
          'X-Render-Origin-Server': 'render-iad-01',
          'Custom-App-Header': 'AllowedValue',
        });
        return new Response(JSON.stringify({ status: 'UP', service: 'queueless' }), {
          status: 200,
          headers,
        });
      };

      const request = new Request(`${workerOrigin}/api/public/queue/vapid-public-key`, {
        method: 'GET',
      });
      const env = {
        ASSETS: { fetch: async () => new Response('assets') },
        BACKEND_API_URL: backendTarget,
      };

      const response = await worker.fetch(request, env);
      assert.equal(response.status, 200);

      // Verify prohibited disclosure headers were completely stripped
      assert.equal(response.headers.get('server'), null);
      assert.equal(response.headers.get('via'), null);
      assert.equal(response.headers.get('x-powered-by'), null);
      assert.equal(response.headers.get('x-cloud-trace-context'), null);
      assert.equal(response.headers.get('x-render-origin-server'), null);

      // Verify legitimate application headers are preserved
      assert.equal(response.headers.get('content-type'), 'application/json');
      assert.equal(response.headers.get('custom-app-header'), 'AllowedValue');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('sanitizes upstream fetch errors without exposing private backend hostname or stack traces', async () => {
    const originalFetch = globalThis.fetch;
    try {
      // Mock network failure containing internal hostnames and provider details
      globalThis.fetch = async () => {
        throw new Error(`getaddrinfo ENOTFOUND ${backendTarget}: connection refused by asia-south1 internal routing`);
      };

      const request = new Request(`${workerOrigin}/api/queue/dashboard`, {
        method: 'GET',
      });
      const env = {
        ASSETS: { fetch: async () => new Response('assets') },
        BACKEND_API_URL: backendTarget,
      };

      const response = await worker.fetch(request, env);
      assert.equal(response.status, 502);
      assert.equal(response.headers.get('content-type'), 'application/json');

      const bodyText = await response.text();
      // Must NOT contain backend host, internal IP, or raw exception message
      assert.ok(!bodyText.includes('1043223278240'));
      assert.ok(!bodyText.includes('asia-south1'));
      assert.ok(!bodyText.includes('ENOTFOUND'));
      assert.ok(!bodyText.includes('run.app'));

      const json = JSON.parse(bodyText);
      assert.equal(json.status, 502);
      assert.equal(json.error, 'Bad Gateway: Upstream service temporarily unavailable.');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('sanitizes upstream 503 HTML error pages (e.g. Google Frontend 503) into clean JSON without provider headers', async () => {
    const originalFetch = globalThis.fetch;
    try {
      // Mock Google Frontend returning HTML error page
      globalThis.fetch = async () => {
        return new Response('<html><title>503 Service Unavailable</title><body>The service you requested is not available yet.</body></html>', {
          status: 503,
          headers: new Headers({
            'Content-Type': 'text/html; charset=UTF-8',
            'Server': 'Google Frontend',
            'X-Cloud-Trace-Context': 'trace123',
          }),
        });
      };

      const request = new Request(`${workerOrigin}/api/public/queue/vapid-public-key`, {
        method: 'GET',
      });
      const env = {
        ASSETS: { fetch: async () => new Response('assets') },
        BACKEND_API_URL: backendTarget,
      };

      const response = await worker.fetch(request, env);
      assert.equal(response.status, 503);
      assert.equal(response.headers.get('content-type'), 'application/json');
      assert.equal(response.headers.get('server'), null);
      assert.equal(response.headers.get('x-cloud-trace-context'), null);

      const json = await response.json();
      assert.equal(json.status, 503);
      assert.equal(json.error, 'Upstream service temporarily unavailable. Please retry shortly.');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('preserves SSE streaming headers and unbuffered real-time transport', async () => {
    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async () => {
        return new Response('data: {"type":"PING"}\n\n', {
          status: 200,
          headers: new Headers({
            'Content-Type': 'text/event-stream; charset=utf-8',
            'Server': 'Google Frontend',
          }),
        });
      };

      const request = new Request(`${workerOrigin}/api/queue/stream/owner?ticket=stk_test123`, {
        method: 'GET',
      });
      const env = {
        ASSETS: { fetch: async () => new Response('assets') },
        BACKEND_API_URL: backendTarget,
      };

      const response = await worker.fetch(request, env);
      assert.equal(response.status, 200);
      assert.equal(response.headers.get('content-type'), 'text/event-stream; charset=utf-8');
      assert.equal(response.headers.get('cache-control'), 'no-cache, no-transform');
      assert.equal(response.headers.get('x-accel-buffering'), 'no');
      assert.equal(response.headers.get('referrer-policy'), 'no-referrer');
      assert.equal(response.headers.get('server'), null);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('redacts sensitive query parameters (tickets, tokens, passwords) in sanitizeUrlForLogging', () => {
    const rawUrl = 'https://queueless.theabhisheksunny.workers.dev/api/queue/stream/owner?ticket=stk_sec123456&shopId=42';
    assert.equal(
      sanitizeUrlForLogging(rawUrl),
      'https://queueless.theabhisheksunny.workers.dev/api/queue/stream/owner?ticket=[REDACTED]&shopId=42'
    );

    const tokenUrl = 'https://example.com/api/test?token=secret_jwt&password=mypassword&pin=1234';
    assert.equal(
      sanitizeUrlForLogging(tokenUrl),
      'https://example.com/api/test?token=[REDACTED]&password=[REDACTED]&pin=[REDACTED]'
    );
  });

  it('enforces Referrer-Policy: no-referrer on all responses (API proxy, gateway errors, static assets)', async () => {
    // 1. API proxy response
    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async () => new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: new Headers({ 'Content-Type': 'application/json' }),
      });

      const apiReq = new Request(`${workerOrigin}/api/public/queue/vapid-public-key`);
      const env = {
        ASSETS: { fetch: async () => new Response('assets', { headers: new Headers({ 'Content-Type': 'text/html' }) }) },
        BACKEND_API_URL: backendTarget,
      };

      const apiRes = await worker.fetch(apiReq, env);
      assert.equal(apiRes.headers.get('referrer-policy'), 'no-referrer');

      // 2. Static asset response
      const staticReq = new Request(`${workerOrigin}/index.html`);
      const staticRes = await worker.fetch(staticReq, env);
      assert.equal(staticRes.headers.get('referrer-policy'), 'no-referrer');

      // 3. 502 Gateway error response
      globalThis.fetch = async () => { throw new Error('Network timeout'); };
      const errRes = await worker.fetch(apiReq, env);
      assert.equal(errRes.status, 502);
      assert.equal(errRes.headers.get('referrer-policy'), 'no-referrer');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
