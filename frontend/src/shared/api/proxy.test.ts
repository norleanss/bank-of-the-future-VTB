import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { createServer as createViteServer, type ViteDevServer } from 'vite';

let upstream: Server;
let vite: ViteDevServer;
let baseUrl: string;

beforeAll(async () => {
  upstream = createServer(async (request, response) => {
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(Buffer.from(chunk));
    response.writeHead(request.url?.endsWith('/submit') ? 202 : 200, {
      'Content-Type': 'application/json',
    });
    response.end(
      JSON.stringify({
        path: request.url,
        method: request.method,
        authorization: request.headers.authorization,
        contentType: request.headers['content-type'],
        body: Buffer.concat(chunks).toString(),
      }),
    );
  });
  await new Promise<void>((resolve) => upstream.listen(0, '127.0.0.1', resolve));
  vi.stubEnv('API_PROXY_TARGET', `http://127.0.0.1:${(upstream.address() as AddressInfo).port}`);
  vite = await createViteServer({
    mode: 'test',
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
  });
  await vite.listen();
  baseUrl = `http://127.0.0.1:${(vite.httpServer!.address() as AddressInfo).port}`;
}, 20_000);

afterAll(async () => {
  await vite?.close();
  if (upstream) await new Promise<void>((resolve) => upstream.close(() => resolve()));
  vi.unstubAllEnvs();
});

describe('development API proxy', () => {
  it('forwards the API prefix, query parameters, authorization and JSON Merge Patch', async () => {
    const body = JSON.stringify({ financials: { annualRevenue: '1000.05', annualExpenses: null } });
    const response = await fetch(`${baseUrl}/api/v1/applications/draft?companyId=company`, {
      method: 'PATCH',
      headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' },
      body,
    });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      path: '/api/v1/applications/draft?companyId=company',
      method: 'PATCH',
      authorization: 'Bearer test-token',
      contentType: 'application/json',
      body,
    });
  });
  it('preserves the submission response status', async () => {
    const response = await fetch(`${baseUrl}/api/v1/applications/draft/submit`, { method: 'POST' });
    expect(response.status).toBe(202);
  });
});
