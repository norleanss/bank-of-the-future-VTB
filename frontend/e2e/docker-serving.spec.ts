import { test, expect } from '@playwright/test';
import type { CreditApplication, Session } from '../src/shared/types/models';

test('Nginx serves the compiled SPA, preserves API routes and streams real notifications', async ({
  request,
  baseURL,
}) => {
  const home = await request.get('/');
  expect(home.status()).toBe(200);
  const html = await home.text();
  expect(html).not.toContain('/@vite/client');
  expect(html).not.toContain('/src/main.tsx');
  const script = html.match(/src="(\/assets\/[^"\s]+\.js)"/)?.[1];
  expect(script).toBeTruthy();
  const asset = await request.get(script!);
  expect(asset.status()).toBe(200);
  expect(asset.headers()['cache-control']).toContain('immutable');
  const deepLink = await request.get('/applications/new');
  expect(deepLink.status()).toBe(200);
  expect(await deepLink.text()).toBe(html);
  expect(deepLink.headers()['cache-control']).toContain('no-store');
  expect((await request.get('/assets/missing.js')).status()).toBe(404);
  const readiness = await request.get('/api/v1/actuator/health/readiness');
  expect(readiness.status()).toBe(200);
  expect(await readiness.json()).toMatchObject({ status: 'UP' });

  const login = await request.post('/api/v1/auth/demo-login', { data: { role: 'CLIENT' } });
  expect(login.ok()).toBe(true);
  const session = (await login.json()) as Session;
  const headers = { Authorization: `Bearer ${session.accessToken}` };
  const stream = await fetch(
    `${baseURL}/api/v1/notifications/stream?access_token=${encodeURIComponent(session.accessToken)}`,
    {
      signal: AbortSignal.timeout(20_000),
    },
  );
  expect(stream.status).toBe(200);
  const reader = stream.body!.getReader();
  const decoder = new TextDecoder();
  let events = '';
  try {
    while (!/event:\s*ping/.test(events)) {
      const chunk = await reader.read();
      expect(chunk.done).toBe(false);
      events += decoder.decode(chunk.value, { stream: true });
    }
    const created = await request.post('/api/v1/applications', {
      headers,
      data: {
        companyId: session.user.companies![0]!.id,
        productCode: 'LUMP_SUM_LOAN',
        purpose: 'EQUIPMENT',
        requestedAmount: '500000.00',
        term: 24,
        termUnit: 'MONTHS',
        fundingMode: 'LUMP_SUM',
        repaymentType: 'ANNUITY',
        financials: {
          annualRevenue: '20000000.00',
          annualExpenses: '14000000.00',
          monthlyDebtPayment: '50000.00',
        },
      },
    });
    expect(created.status()).toBe(201);
    const app = (await created.json()) as CreditApplication;
    const submitted = await request.post(`/api/v1/applications/${app.id}/submit`, { headers });
    expect(submitted.status()).toBe(202);
    while (!events.includes(app.id)) {
      const chunk = await reader.read();
      expect(chunk.done).toBe(false);
      events += decoder.decode(chunk.value, { stream: true });
    }
    expect(events).toMatch(/event:\s*notification/);
  } finally {
    await reader.cancel().catch(() => {});
  }
});
