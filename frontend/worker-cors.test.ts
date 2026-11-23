import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import worker, { isAllowedOrigin } from './worker.ts';

describe('Cloudflare Worker CORS Security & Origin Validation', () => {
  const workerOrigin = 'https://queueless.theabhisheksunny.workers.dev';

  it('allows same-origin requests (null Origin header)', () => {
    assert.equal(isAllowedOrigin(null, workerOrigin), true);
  });

  it('allows direct worker origin match', () => {
    assert.equal(isAllowedOrigin(workerOrigin, workerOrigin), true);
  });

  it('allows production frontend origin', () => {
    assert.equal(isAllowedOrigin('https://queueless.theabhisheksunny.workers.dev', workerOrigin), true);
  });

  it('allows staging frontend origin', () => {
    assert.equal(isAllowedOrigin('https://queueless-staging.theabhisheksunny.workers.dev', workerOrigin), true);
  });

  it('allows localhost development origins on any port', () => {
    assert.equal(isAllowedOrigin('http://localhost:5173', workerOrigin), true);
    assert.equal(isAllowedOrigin('http://localhost:8080', workerOrigin), true);
    assert.equal(isAllowedOrigin('http://127.0.0.1:5173', workerOrigin), true);
    assert.equal(isAllowedOrigin('http://127.0.0.1:8080', workerOrigin), true);
  });

  it('allows origins configured in ALLOWED_ORIGINS env variable', () => {
    const customOrigin = 'https://custom-partner.queueless.in';
    assert.equal(isAllowedOrigin(customOrigin, workerOrigin, 'https://custom-partner.queueless.in, https://other.in'), true);
  });

  it('rejects untrusted third-party origins', () => {
    assert.equal(isAllowedOrigin('https://malicious-site.com', workerOrigin), false);
    assert.equal(isAllowedOrigin('https://evil-hacker.io', workerOrigin), false);
    assert.equal(isAllowedOrigin('https://queueless.theabhisheksunny.evil.com', workerOrigin), false);
    assert.equal(isAllowedOrigin('http://localhost.evil.com', workerOrigin), false);
  });

  it('blocks cross-origin preflight (OPTIONS) from untrusted origin with 403', async () => {
    const request = new Request('https://queueless.theabhisheksunny.workers.dev/api/health', {
      method: 'OPTIONS',
      headers: {
        'Origin': 'https://attacker-domain.com',
        'Access-Control-Request-Method': 'GET',
      },
    });

    const env = {
      ASSETS: { fetch: async () => new Response('assets') },
      BACKEND_API_URL: 'https://backend-test.local',
    };

    const response = await worker.fetch(request, env);
    assert.equal(response.status, 403);
    assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
  });

  it('approves cross-origin preflight (OPTIONS) from trusted origin with 204', async () => {
    const request = new Request('https://queueless.theabhisheksunny.workers.dev/api/health', {
      method: 'OPTIONS',
      headers: {
        'Origin': 'https://queueless-staging.theabhisheksunny.workers.dev',
        'Access-Control-Request-Method': 'GET',
      },
    });

    const env = {
      ASSETS: { fetch: async () => new Response('assets') },
      BACKEND_API_URL: 'https://backend-test.local',
    };

    const response = await worker.fetch(request, env);
    assert.equal(response.status, 204);
    assert.equal(response.headers.get('Access-Control-Allow-Origin'), 'https://queueless-staging.theabhisheksunny.workers.dev');
    assert.equal(response.headers.get('Access-Control-Allow-Credentials'), 'true');
    assert.equal(response.headers.get('Vary'), 'Origin');
  });

  it('blocks actual API request from untrusted origin with 403 Forbidden', async () => {
    const request = new Request('https://queueless.theabhisheksunny.workers.dev/api/tokens', {
      method: 'POST',
      headers: {
        'Origin': 'https://evil-phishing.org',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ token: 'test' }),
    });

    const env = {
      ASSETS: { fetch: async () => new Response('assets') },
      BACKEND_API_URL: 'https://backend-test.local',
    };

    const response = await worker.fetch(request, env);
    assert.equal(response.status, 403);
    assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
  });
});
