import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { httpApi } from './http';
import { saveSession, readSession } from '../lib/storage';
import type { Session } from '../types/models';
const memory = new Map<string, string>();
const fetchMock = vi.fn<typeof fetch>();
const session: Session = {
  accessToken: 'demo-token',
  expiresIn: 86400,
  user: {
    id: 'user',
    fullName: 'Иван',
    role: 'CLIENT',
    companies: [{ id: 'company', name: 'Компания', inn: '7841256395', businessType: 'LLC' }],
  },
};
beforeEach(() => {
  memory.clear();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  vi.stubGlobal('window', new EventTarget());
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => memory.get(key) ?? null,
    setItem: (key: string, value: string) => memory.set(key, value),
    removeItem: (key: string) => memory.delete(key),
  });
});
afterEach(() => vi.unstubAllGlobals());
const respond = (data: unknown, status = 200) =>
  fetchMock.mockResolvedValueOnce(
    new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } }),
  );
describe('HTTP adapter', () => {
  it('uses demo role login and persists the company list', async () => {
    respond(session);
    await httpApi.login('CLIENT');
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/auth/demo-login');
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({ role: 'CLIENT' });
    expect(readSession()?.user.companies).toHaveLength(1);
  });
  it('sends company, exact decimal amount, day term and subscription', async () => {
    saveSession(session);
    respond({ items: [] });
    const data = {
      companyId: 'company',
      purpose: 'CASH_GAP',
      requestedAmount: '1500000.05',
      term: 75,
      termUnit: 'DAYS',
      fundingMode: 'ON_DEMAND',
    } as const;
    await httpApi.matchProducts(data);
    const options = fetchMock.mock.calls[0]?.[1];
    expect(JSON.parse(String(options?.body))).toEqual(data);
    expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer demo-token');
    respond({ id: 'draft' });
    await httpApi.createApplication({ ...data, subscription: true, productCode: 'OVERDRAFT' });
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toMatchObject({
      requestedAmount: '1500000.05',
      subscription: true,
    });
  });
  it('preserves PATCH null and dotted validation paths', async () => {
    saveSession(session);
    respond(
      {
        title: 'Проверьте поля',
        errors: [{ field: 'financials.annualRevenue', message: 'Введите выручку' }],
      },
      400,
    );
    await expect(
      httpApi.updateApplication('draft', { financials: { annualRevenue: null } }),
    ).rejects.toMatchObject({
      status: 400,
      fields: [{ field: 'financials.annualRevenue', message: 'Введите выручку' }],
    });
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
      financials: { annualRevenue: null },
    });
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('Content-Type')).toBe(
      'application/json',
    );
  });
  it('keeps offer expiration distinguishable and clears an expired session', async () => {
    saveSession(session);
    respond(
      { title: 'Предложение истекло', type: 'https://credit.example/problems/offer-expired' },
      409,
    );
    await expect(httpApi.acceptOffer('application')).rejects.toMatchObject({
      status: 409,
      type: 'https://credit.example/problems/offer-expired',
    });
    const listener = vi.fn();
    window.addEventListener('session-expired', listener);
    respond({ title: 'Войдите заново' }, 401);
    expect(await httpApi.getSession()).toBeNull();
    expect(readSession()).toBeNull();
    expect(listener).toHaveBeenCalledOnce();
  });
  it('lets the browser choose a multipart boundary and filters all-company lists', async () => {
    saveSession(session);
    respond({ id: 'document' });
    await httpApi.uploadDocument(
      'application',
      new File(['report'], 'report.pdf', { type: 'application/pdf' }),
      'FINANCIAL_STATEMENT',
    );
    const options = fetchMock.mock.calls[0]?.[1];
    expect(options?.body).toBeInstanceOf(FormData);
    expect(new Headers(options?.headers).has('Content-Type')).toBe(false);
    respond({ content: [] });
    await httpApi.getApplications('company', 2);
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      '/api/v1/applications?page=2&size=10&companyId=company',
    );
  });
  it('handles the server draft lifecycle and a 202 submission response', async () => {
    saveSession(session);
    respond({ id: 'draft', status: 'DRAFT' }, 201);
    await httpApi.createApplication({ companyId: 'company', productCode: 'OVERDRAFT' });
    const patch = {
      term: 60,
      termUnit: 'DAYS',
      financials: { monthlyDebtPayment: '0.00' },
    } as const;
    respond({ id: 'draft', status: 'DRAFT', ...patch });
    await httpApi.updateApplication('draft', patch);
    respond({ id: 'draft', status: 'SUBMITTED' }, 202);
    expect(await httpApi.submitApplication('draft')).toMatchObject({ status: 'SUBMITTED' });
    respond({ id: 'draft', status: 'SUBMITTED' });
    await httpApi.getApplication('draft');
    respond({ id: 'draft', status: 'CANCELLED' });
    await httpApi.cancelApplication('draft');
    expect(fetchMock.mock.calls.map(([url, options]) => [url, options?.method ?? 'GET'])).toEqual([
      ['/api/v1/applications', 'POST'],
      ['/api/v1/applications/draft', 'PATCH'],
      ['/api/v1/applications/draft/submit', 'POST'],
      ['/api/v1/applications/draft', 'GET'],
      ['/api/v1/applications/draft/cancel', 'POST'],
    ]);
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual(patch);
  });
  it('reports unavailable operations without replacing a server response with mock data', async () => {
    saveSession(session);
    respond({ title: 'Not Implemented', status: 501 }, 501);
    await expect(httpApi.requestSigningCode('application')).rejects.toMatchObject({
      status: 501,
      message: 'Это действие пока недоступно. Попробуйте позже.',
    });
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(readSession()?.accessToken).toBe('demo-token');
  });
  it('reports a network failure and keeps an authenticated session', async () => {
    saveSession(session);
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await expect(httpApi.getCompanies()).rejects.toMatchObject({ status: 0 });
    expect(readSession()?.accessToken).toBe('demo-token');
  });
  it('downloads document bytes using the correct role endpoint and authorization', async () => {
    saveSession(session);
    for (const backoffice of [false, true]) {
      fetchMock.mockResolvedValueOnce(
        new Response('%PDF-1.4 content', { headers: { 'Content-Type': 'application/pdf' } }),
      );
      const blob = await httpApi.getDocumentContent('application', 'document', backoffice);
      expect(await blob.text()).toBe('%PDF-1.4 content');
      expect(blob.type).toBe('application/pdf');
    }
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/applications/application/documents/document/content',
      '/api/v1/backoffice/applications/application/documents/document/content',
    ]);
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get('Authorization')).toBe(
      'Bearer demo-token',
    );
  });
  it('paginates unread notifications and handles a read acknowledgment with no body', async () => {
    saveSession(session);
    respond({ content: [], page: 2, size: 20, totalElements: 0, totalPages: 0 });
    await httpApi.getNotifications(true, 2);
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/v1/notifications?unreadOnly=true&page=2&size=20',
    );
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await expect(httpApi.markNotificationRead('notification')).resolves.toBeUndefined();
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/notifications/notification/read');
  });
  it('reconciles notifications on SSE reconnect and closes the stream on cleanup', () => {
    saveSession(session);
    const stream = new EventTarget();
    const close = vi.fn();
    const source = vi.fn(function () {
      return Object.assign(stream, { close });
    });
    vi.stubGlobal('EventSource', source);
    const onChange = vi.fn();
    const cleanup = httpApi.subscribeNotifications!(onChange);
    expect(source).toHaveBeenCalledWith('/api/v1/notifications/stream?access_token=demo-token');
    stream.dispatchEvent(new Event('open'));
    stream.dispatchEvent(new Event('notification'));
    stream.dispatchEvent(new Event('open'));
    expect(onChange).toHaveBeenCalledTimes(3);
    cleanup();
    expect(close).toHaveBeenCalledOnce();
  });
});
