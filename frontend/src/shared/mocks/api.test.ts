import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { mockApi, DEMO_STAGE_MS } from './api';
import { readState, writeState } from './storage';
import { companies } from './fixtures';
import { getWorkspace, saveWorkspace, selectCompany, readSession } from '../lib/storage';
import type { ApplicationCreateRequest } from '../types/models';

const memory = new Map<string, string>();
const call = <T>(promise: Promise<T>) =>
  Promise.all([promise, vi.runAllTimersAsync()]).then(([value]) => value);
const companyId = companies[0]!.id;
const request: ApplicationCreateRequest = {
  companyId,
  productCode: 'LUMP_SUM_LOAN',
  purpose: 'EQUIPMENT',
  requestedAmount: '2000000.00',
  term: 24,
  termUnit: 'MONTHS',
  fundingMode: 'LUMP_SUM',
  repaymentType: 'ANNUITY',
  subscription: false,
  financials: {
    annualRevenue: '12000000.00',
    annualExpenses: '8000000.00',
    bankMonthlyTurnover: '1000000.00',
    monthlyDebtPayment: '0.00',
  },
};
beforeEach(() => {
  vi.useFakeTimers();
  memory.clear();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => memory.get(key) ?? null,
    setItem: (key: string, value: string) => memory.set(key, value),
    removeItem: (key: string) => memory.delete(key),
  });
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
async function submitted(data = request) {
  await call(mockApi.login('CLIENT'));
  const app = await call(mockApi.createApplication(data));
  await call(mockApi.submitApplication(app.id));
  return app;
}
describe('mock API 0.2', () => {
  it('does not submit an incomplete server draft', async () => {
    await call(mockApi.login('CLIENT'));
    const app = await call(mockApi.createApplication({ companyId }));
    await expect(call(mockApi.submitApplication(app.id))).rejects.toMatchObject({ status: 400 });
    expect(readState().applications[0]?.status).toBe('DRAFT');
  });
  it('keeps a counter offer within the minimum product amount', async () => {
    const app = await submitted({ ...request, requestedAmount: '300000.00' });
    const counter = await call(mockApi.setDemoDecision!(app.id, 'COUNTER_OFFER'));
    expect(counter.offer?.amount).toBe('300000.00');
    expect(counter.offer?.rate).not.toBe(23.5);
  });
  it('separates company drafts and persists selection and server draft id', async () => {
    await call(mockApi.login('CLIENT'));
    selectCompany(companyId);
    saveWorkspace(
      { applicationId: 'draft-a', selection: { term: 75, termUnit: 'DAYS' } },
      companyId,
    );
    selectCompany(companies[1]!.id);
    expect(getWorkspace().applicationId).toBeUndefined();
    selectCompany(companyId);
    expect(getWorkspace().applicationId).toBe('draft-a');
    expect(getWorkspace().selection.term).toBe(75);
    expect(readSession()?.user.companies).toHaveLength(6);
  });
  it('marks subscription-only matches and checks company age', async () => {
    await call(mockApi.login('CLIENT'));
    const matched = await call(
      mockApi.matchProducts({
        companyId,
        purpose: 'CASH_GAP',
        requestedAmount: '1500000.00',
        term: 75,
        termUnit: 'DAYS',
        fundingMode: 'ON_DEMAND',
      }),
    );
    expect(matched.items.find((item) => item.product.code === 'OVERDRAFT')).toMatchObject({
      suitable: false,
      requiresSubscription: true,
    });
    const young = await call(
      mockApi.matchProducts({
        companyId: companies[4]!.id,
        purpose: 'WORKING_CAPITAL',
        requestedAmount: '500000.00',
        term: 6,
        termUnit: 'MONTHS',
        fundingMode: 'TRANCHES',
      }),
    );
    expect(young.items.find((item) => item.product.code === 'CREDIT_LINE')).toMatchObject({
      suitable: false,
      requiresSubscription: false,
    });
  });
  it('merges nested PATCH fields and clears values without returning null', async () => {
    await call(mockApi.login('CLIENT'));
    const app = await call(mockApi.createApplication(request));
    const changed = await call(
      mockApi.updateApplication(app.id, {
        financials: { annualRevenue: '1000.00', annualExpenses: null },
      }),
    );
    expect(changed.financials).toEqual({
      annualRevenue: '1000.00',
      bankMonthlyTurnover: '1000000.00',
      monthlyDebtPayment: '0.00',
    });
    const cleared = await call(mockApi.updateApplication(app.id, { financials: null }));
    expect('financials' in cleared).toBe(false);
  });
  it('progresses through approval and signing to disbursement', async () => {
    const app = await submitted();
    const repeated = await call(mockApi.submitApplication(app.id));
    expect(repeated.submittedAt).toBeDefined();
    await vi.advanceTimersByTimeAsync(DEMO_STAGE_MS * 4);
    const approved = await call(mockApi.getApplication(app.id));
    expect(approved.status).toBe('APPROVED');
    expect(approved.offer?.amount).toBe('2000000.00');
    expect(approved.availableActions).toContain('ACCEPT_OFFER');
    expect((await call(mockApi.acceptOffer(app.id))).status).toBe('OFFER_ACCEPTED');
    const code = await call(mockApi.requestSigningCode(app.id));
    await expect(call(mockApi.confirmSigning(app.id, '0000'))).rejects.toThrow('Код неверный');
    expect((await call(mockApi.confirmSigning(app.id, code.demoCode!))).status).toBe('DISBURSED');
    expect(readState().applications[0]?.status).toBe('DISBURSED');
  });
  it('expires offers and prevents acceptance', async () => {
    const app = await submitted();
    await call(mockApi.setDemoDecision!(app.id, 'APPROVED'));
    await vi.advanceTimersByTimeAsync(8 * 86400_000);
    await call(mockApi.login('CLIENT'));
    const expired = await call(mockApi.getApplication(app.id));
    expect(expired.status).toBe('OFFER_EXPIRED');
    expect(expired.availableActions).toEqual([]);
    await expect(call(mockApi.acceptOffer(app.id))).rejects.toMatchObject({
      status: 409,
      type: 'https://credit.example/problems/offer-expired',
    });
  });
  it('keeps pending confirmation active after expiration and requires a bank role', async () => {
    const app = await submitted({ ...request, companyId: companies[3]!.id });
    await call(mockApi.setDemoDecision!(app.id, 'APPROVED'));
    expect((await call(mockApi.acceptOffer(app.id))).status).toBe('PENDING_CONFIRMATION');
    await vi.advanceTimersByTimeAsync(8 * 86400_000);
    await call(mockApi.login('CLIENT'));
    expect((await call(mockApi.getApplication(app.id))).status).toBe('PENDING_CONFIRMATION');
    await expect(call(mockApi.makeDecision(app.id, { decision: 'APPROVE' }))).rejects.toMatchObject(
      { status: 403 },
    );
    await call(mockApi.logout());
    await call(mockApi.login('UNDERWRITER'));
    await expect(
      call(mockApi.makeDecision(app.id, { decision: 'APPROVE', amount: '1000000.00' })),
    ).rejects.toMatchObject({ status: 400 });
    const result = await call(
      mockApi.makeDecision(app.id, {
        decision: 'APPROVE',
      }),
    );
    expect(result.status).toBe('OFFER_ACCEPTED');
  });
  it('requires requested documents and preserves completed uploads across reloads', async () => {
    const app = await submitted();
    await call(mockApi.setDemoDecision!(app.id, 'MANUAL_REVIEW'));
    await call(mockApi.login('UNDERWRITER'));
    const info = await call(
      mockApi.requestInfo(app.id, {
        message: 'Уточните выручку',
        requestedFields: ['financials.annualRevenue'],
        requestedDocuments: ['FINANCIAL_STATEMENT'],
      }),
    );
    await call(mockApi.login('CLIENT'));
    await call(
      mockApi.updateApplication(app.id, {
        financials: { annualRevenue: '13000000.00' },
      }),
    );
    await expect(call(mockApi.respondInfo(app.id, info.id, { documentIds: [] }))).rejects.toThrow(
      'Загрузите',
    );
    const doc = await call(
      mockApi.uploadDocument(
        app.id,
        new File(['demo'], 'report.pdf', { type: 'application/pdf' }),
        'FINANCIAL_STATEMENT',
      ),
    );
    expect(readState().documents[app.id]?.[0]?.id).toBe(doc.id);
    expect(await (await call(mockApi.getDocumentContent(app.id, doc.id))).text()).toBe('demo');
    expect(
      (await call(mockApi.respondInfo(app.id, info.id, { documentIds: [doc.id] }))).status,
    ).toBe('MANUAL_REVIEW');
    expect(readState().documents[app.id]?.[0]?.infoRequestId).toBe(info.id);
    await call(mockApi.login('UNDERWRITER'));
    const detail = await call(mockApi.getBackofficeApplication(app.id));
    expect(detail.infoRequests[0]?.status).toBe('ANSWERED');
    expect(detail.documents[0]?.id).toBe(doc.id);
    const nextRequest = await call(
      mockApi.requestInfo(app.id, {
        message: 'Ещё одна отчётность',
        requestedFields: [],
        requestedDocuments: ['FINANCIAL_STATEMENT'],
      }),
    );
    await call(mockApi.login('CLIENT'));
    await expect(
      call(mockApi.respondInfo(app.id, nextRequest.id, { documentIds: [doc.id] })),
    ).rejects.toMatchObject({ status: 400 });
  });
  it('preserves notifications and unread application counts across role changes', async () => {
    const app = await submitted();
    await call(mockApi.setDemoDecision!(app.id, 'APPROVED'));
    const notifications = await call(mockApi.getNotifications(true));
    expect(
      notifications.content.some(
        (item) => item.applicationId === app.id && item.type === 'DECISION_MADE',
      ),
    ).toBe(true);
    const first = notifications.content[0]!;
    await call(mockApi.markNotificationRead(first.id));
    expect(
      (await call(mockApi.getNotifications(true))).content.some((item) => item.id === first.id),
    ).toBe(false);
    expect((await call(mockApi.getApplications())).content[0]?.unreadNotifications).toBe(
      notifications.totalElements - 1,
    );
    await call(mockApi.login('UNDERWRITER'));
    await expect(call(mockApi.getNotifications())).rejects.toMatchObject({ status: 403 });
    await call(mockApi.login('CLIENT'));
    expect(
      (await call(mockApi.getNotifications())).content.find((item) => item.id === first.id)?.read,
    ).toBe(true);
  });
  it('filters applications by company and rejects inaccessible companies', async () => {
    await call(mockApi.login('CLIENT'));
    await call(mockApi.createApplication(request));
    await call(mockApi.createApplication({ ...request, companyId: companies[1]!.id }));
    expect((await call(mockApi.getApplications())).totalElements).toBe(2);
    expect((await call(mockApi.getApplications(companyId))).totalElements).toBe(1);
    await expect(call(mockApi.getCompany('missing'))).rejects.toMatchObject({
      status: 403,
    });
    const state = readState();
    state.applications[0]!.companyId = 'foreign';
    writeState(state);
    await expect(call(mockApi.getApplication(state.applications[0]!.id))).rejects.toMatchObject({
      status: 403,
    });
  });
});
