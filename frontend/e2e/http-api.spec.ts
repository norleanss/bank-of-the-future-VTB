import { test, expect, type Page as BrowserPage } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { writeFile } from 'node:fs/promises';
import { companies, dictionaries, products } from '../src/shared/mocks/fixtures';
import type {
  CreditApplication,
  ManualDecisionRequest,
  UploadedDocument,
} from '../src/shared/types/models';

const id = '11111111-1111-4111-8111-111111111111';
const now = '2026-10-24T18:00:00+03:00';
const makeApplication = (): CreditApplication => ({
  id,
  number: 'CR-2026-000123',
  companyId: companies[0]!.id,
  status: 'NEED_INFO',
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
    monthlyDebtPayment: '0.00',
  },
  availableActions: ['RESPOND_INFO', 'UPLOAD_DOCUMENT', 'CANCEL'],
  reasons: [
    {
      code: 'MISSING_FIELDS',
      message: 'Нужно уточнить сведения.',
      advice: 'Пришлите новую отчётность.',
    },
  ],
  openInfoRequest: {
    id: 'request-new',
    message: 'Уточните выручку и приложите отчётность.',
    requestedFields: ['financials.annualRevenue'],
    requestedDocuments: ['FINANCIAL_STATEMENT'],
    createdBy: 'EMPLOYEE',
    createdAt: now,
    status: 'OPEN',
  },
  createdAt: now,
  updatedAt: now,
});

async function apiFixture(page: BrowserPage, app = makeApplication()) {
  const documents: UploadedDocument[] = [
    {
      id: 'old',
      fileName: 'old-report.pdf',
      documentType: 'FINANCIAL_STATEMENT',
      mimeType: 'application/pdf',
      sizeBytes: 20,
      uploadedAt: now,
      infoRequestId: 'request-old',
    },
    {
      id: 'new',
      fileName: 'new-report.pdf',
      documentType: 'FINANCIAL_STATEMENT',
      mimeType: 'application/pdf',
      sizeBytes: 20,
      uploadedAt: now,
    },
  ];
  const calls: { path: string; method: string; body: unknown; contentType?: string }[] = [];
  let role = 'CLIENT';
  let read = false;
  const notification = () => ({
    id: 'notification',
    type: 'INFO_REQUESTED',
    title: 'Банк запросил сведения',
    body: 'Пришлите новую отчётность по заявке CR-2026-000123.',
    applicationId: id,
    createdAt: now,
    read,
  });
  const history = [
    {
      toStatus: 'MANUAL_REVIEW',
      actorType: 'EMPLOYEE',
      actorName: 'Мария Смирнова',
      comment: 'Проверяем отчётность.',
      createdAt: now,
    },
  ];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace('/api/v1', '');
    const body = request.postData() ? request.postDataJSON() : undefined;
    calls.push({
      path,
      method: request.method(),
      body,
      contentType: request.headers()['content-type'],
    });
    const json = (data: unknown, status = 200) => route.fulfill({ status, json: data });
    const user = () => ({
      id: 'user',
      fullName: role === 'CLIENT' ? 'Иван Петров' : 'Мария Смирнова',
      role,
      ...(role === 'CLIENT' ? { companies } : {}),
    });
    if (path === '/auth/demo-login') {
      role = body.role;
      return json({ accessToken: 'http-test-token', expiresIn: 86400, user: user() });
    }
    expect(request.headers().authorization ?? url.searchParams.get('access_token')).toBe(
      path === '/notifications/stream' ? 'http-test-token' : 'Bearer http-test-token',
    );
    if (path === '/me') return json(user());
    if (path === '/notifications/stream')
      return route.fulfill({ contentType: 'text/event-stream', body: 'event: ping\ndata: {}\n\n' });
    if (path === '/notifications/notification/read') {
      read = true;
      return route.fulfill({ status: 204 });
    }
    if (path === '/notifications') {
      const content = url.searchParams.get('unreadOnly') === 'true' && read ? [] : [notification()];
      return json({
        content,
        page: 0,
        size: 20,
        totalPages: content.length ? 1 : 0,
        totalElements: content.length,
      });
    }
    if (path === '/products') return json(products);
    if (path === '/dictionaries') return json(dictionaries);
    if (path === `/companies/${app.companyId}`) return json(companies[0]);
    if (path.endsWith('/documents/new/content'))
      return route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.4 actual bytes' });
    if (path === `/applications/${id}/documents`) return json(documents);
    if (path === `/applications/${id}/history`)
      return json(history.map((item) => ({ ...item, actorName: undefined })));
    if (path === `/applications/${id}/info-requests/request-new/response`) {
      expect(body.documentIds).toEqual(['new']);
      documents[1]!.infoRequestId = 'request-new';
      app = {
        ...app,
        status: 'MANUAL_REVIEW',
        availableActions: ['CANCEL'],
        openInfoRequest: undefined,
        updatedAt: '2026-10-24T18:01:00+03:00',
      };
      return json(app);
    }
    if (path === `/applications/${id}`) {
      if (request.method() === 'PATCH') {
        expect(body).toEqual({ financials: { annualRevenue: '21000000.00' } });
        expect(request.headers()['content-type']).toBe('application/json');
        app.financials = { ...app.financials, ...body.financials };
      }
      return json(app);
    }
    if (path === '/applications' || path === '/backoffice/applications')
      return json({
        content: [],
        page: 0,
        size: 10,
        totalElements: 0,
        totalPages: 0,
      });
    if (path === `/backoffice/applications/${id}/decision`) {
      app = {
        ...app,
        status: app.status === 'PENDING_CONFIRMATION' ? 'OFFER_ACCEPTED' : 'APPROVED',
      };
      return json(app);
    }
    if (path === `/backoffice/applications/${id}`)
      return json({
        application: app,
        company: companies[0],
        documents,
        history,
        infoRequests: [
          {
            id: 'request-old',
            message: 'Предыдущая отчётность',
            requestedFields: [],
            requestedDocuments: ['FINANCIAL_STATEMENT'],
            status: 'ANSWERED',
            createdBy: 'EMPLOYEE',
            createdAt: now,
            response: 'Прикладываю документы за прошлый год.',
          },
        ],
        scoring: {
          score: 70,
          grade: 'B',
          factors: [
            { factor: 'PROFITABILITY', points: 20, maxPoints: 25, explanation: 'Рентабельность' },
          ],
        },
      });
    throw new Error(`Unexpected HTTP call: ${request.method()} ${path}`);
  });
  return calls;
}

test('HTTP client responds with unused documents, downloads files and reads notifications', async ({
  page,
}, testInfo) => {
  const calls = await apiFixture(page);
  await page.goto('/login');
  await page.getByRole('button', { name: 'Войти как клиент', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Здорово, Иван!' })).toBeVisible();
  await page.goto(`/applications/${id}`);
  await expect(page.getByText(/Пришлите новую отчётность\./).first()).toBeVisible();
  await expect(page.getByText('✓ Финансовая отчётность: old-report.pdf')).toHaveCount(0);
  await expect(page.getByText('✓ Финансовая отчётность: new-report.pdf')).toBeVisible();
  await page.getByLabel('Выручка за 12 месяцев, ₽', { exact: true }).fill('21000000');
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Скачать new-report.pdf', exact: true }).click();
  expect((await downloadEvent).suggestedFilename()).toBe('new-report.pdf');
  await expect(
    page.getByRole('button', { name: 'Скачать new-report.pdf', exact: true }),
  ).toBeEnabled();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: testInfo.outputPath('http-need-info.png'), fullPage: true });
  // A static DOM snapshot lets the visual inspector examine an authenticated screen.
  const html = await page.evaluate(() => {
    const snapshot = document.documentElement.cloneNode(true) as HTMLElement;
    snapshot.querySelectorAll('script').forEach((script) => script.remove());
    snapshot.querySelectorAll('input').forEach((input, index) => {
      input.setAttribute('value', document.querySelectorAll('input')[index]?.value ?? '');
    });
    return `<!doctype html>${snapshot.outerHTML}`;
  });
  await writeFile(testInfo.outputPath('http-need-info.html'), html);
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByRole('button', { name: 'Отправить данные', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'На рассмотрении у специалиста', exact: true }),
  ).toBeVisible();
  expect(calls.some((call) => call.method === 'PATCH')).toBe(true);
  await page.getByRole('link', { name: /^Уведомления/ }).click();
  await expect(
    page.getByRole('heading', { name: 'Банк запросил сведения', exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Отметить прочитанным' }).click();
  await expect(page.getByRole('button', { name: 'Отметить прочитанным' })).toHaveCount(0);
  await page.getByRole('tab', { name: 'Непрочитанные', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Всё прочитано' })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('http-notifications.png'), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

for (const sameMessage of [true, false]) {
  test(`HTTP info request preserves distinct reasons without duplicate instructions (${sameMessage})`, async ({ page }) => {
    const app = makeApplication();
    const message = 'Укажите в анкете: выручка за год, расходы за год';
    app.openInfoRequest!.message = message;
    app.reasons[0]!.message = sameMessage ? message : 'Нужно уточнить сведения.';
    await apiFixture(page, app);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Войти как клиент', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Здорово, Иван!' })).toBeVisible();
    await page.goto(`/applications/${id}`);
    await expect(page.getByRole('heading', { name: 'Нужны дополнительные данные', exact: true }))
      .toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(message, { exact: true })).toHaveCount(1);
    if (!sameMessage)
      await expect(page.getByText('Нужно уточнить сведения.', { exact: true })).toBeVisible();
    await expect(page.locator('p.notice')).toContainText('Пришлите новую отчётность.');
    await expect(page.getByLabel('Выручка за 12 месяцев, ₽', { exact: true })).toBeVisible();
  });
}

for (const status of ['MANUAL_REVIEW', 'PENDING_CONFIRMATION'] as const) {
  test(`HTTP underwriter approves ${status} without resending terms`, async ({
    page,
  }, testInfo) => {
    const app: CreditApplication = {
      ...makeApplication(),
      status,
      openInfoRequest: undefined,
      availableActions: [],
      offer: {
        productCode: 'LUMP_SUM_LOAN',
        productName: 'Кредит одной суммой',
        amount: '500000.00',
        term: 24,
        termUnit: 'MONTHS',
        termKind: 'LOAN',
        rate: 23.5,
        rateUnit: 'PERCENT_PER_YEAR',
        subscription: false,
        preliminary: true,
        expiresAt: '2026-10-31T18:00:00+03:00',
      },
    };
    const calls = await apiFixture(page, app);
    await page.goto('/login');
    await page.getByRole('button', { name: 'Войти как сотрудник банка', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Очередь заявок', exact: true })).toBeVisible();
    await page.goto(`/backoffice/applications/${id}`);
    await expect(
      page.getByText('Прикладываю документы за прошлый год.', { exact: false }),
    ).toBeVisible();
    await expect(page.getByText(/Мария Смирнова/).last()).toBeVisible();
    await expect(page.getByLabel('Сумма предложения, ₽', { exact: true })).toHaveCount(0);
    const downloadEvent = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Скачать new-report.pdf', exact: true }).click();
    await downloadEvent;
    expect(calls.some((call) => call.path.startsWith('/applications/'))).toBe(false);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: testInfo.outputPath(`http-${status.toLowerCase()}.png`),
      fullPage: true,
    });
    await page.getByRole('button', { name: 'Сохранить решение', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: 'Действия не требуются', exact: true }),
    ).toBeVisible();
    const decision = calls.find((call) => call.path.endsWith('/decision'))!
      .body as ManualDecisionRequest;
    expect(decision).toEqual({ decision: 'APPROVE', comment: '', reasonCodes: [] });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  });
}
