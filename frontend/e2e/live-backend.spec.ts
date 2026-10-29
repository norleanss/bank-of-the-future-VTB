import { test, expect, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import type {
  ApplicationCreateRequest,
  CreditApplication,
  Session,
} from '../src/shared/types/models';
import { login, selectCompany, changeRole, chooseProduct } from './helpers';

// Requires the actual Spring Boot API, PostgreSQL and WireMock. No request interception.
async function api<T>(page: Page, path: string, method = 'GET', data?: unknown): Promise<T> {
  const token = await page.evaluate(() => {
    const stored = JSON.parse(localStorage.getItem('gamma-credit:session:v1:http') ?? 'null');
    return stored?.session.accessToken as string | undefined;
  });
  const response = await page.request.fetch(`/api/v1${path}`, {
    method,
    data,
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`).toBe(
    true,
  );
  return response.status() === 204 ? (undefined as T) : response.json();
}

async function createSubmitted(
  page: Page,
  companyName = 'Северный ветер',
  overrides: Partial<ApplicationCreateRequest> = {},
) {
  const user = await api<Session['user']>(page, '/me');
  const company = user.companies!.find((item) => item.name.includes(companyName))!;
  const app = await api<CreditApplication>(page, '/applications', 'POST', {
    companyId: company.id,
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
    ...overrides,
  });
  await api(page, `/applications/${app.id}/submit`, 'POST');
  return app;
}

async function awaitStatus(page: Page, id: string, status: CreditApplication['status']) {
  await expect
    .poll(async () => (await api<CreditApplication>(page, `/applications/${id}`)).status, {
      timeout: 30_000,
      intervals: [500, 1000, 2000],
    })
    .toBe(status);
}

async function sign(page: Page) {
  await page.getByRole('button', { name: 'Получить код для подписания', exact: true }).click();
  const code = await page.getByTestId('signing-code').innerText();
  await page.getByLabel('Код подтверждения', { exact: true }).fill(code);
  await page.getByRole('button', { name: 'Подписать договор', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Деньги выданы', exact: true })).toBeVisible({
    timeout: 30_000,
  });
}

test('live UI application, real processing, signing, notifications and persistence', async ({
  page,
}, testInfo) => {
  const streamReady = page.waitForResponse((response) =>
    response.url().includes('/notifications/stream?'),
  );
  await login(page);
  expect((await streamReady).headers()['content-type']).toContain('text/event-stream');
  await page.getByRole('link', { name: 'Подобрать кредит', exact: true }).click();
  await page.getByText('Оборудование и развитие', { exact: true }).click();
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByLabel('Необходимая сумма, ₽', { exact: true }).fill('500000');
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByLabel('Срок, месяцев', { exact: true }).fill('24');
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  const matchResponse = page.waitForResponse((response) =>
    response.url().endsWith('/products/match'),
  );
  await page.getByRole('button', { name: 'Показать подходящие продукты', exact: true }).click();
  expect((await matchResponse).status()).toBe(200);
  const card = page
    .getByRole('article')
    .filter({ has: page.getByRole('heading', { name: 'Кредит одной суммой', exact: true }) });
  await expect(card.getByRole('heading', { name: 'Платёж в месяц', exact: true })).toBeVisible();
  await card.getByRole('button', { name: 'Оформить', exact: true }).click();
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByLabel('Выручка за 12 месяцев, ₽', { exact: true }).fill('20000000');
  await page.getByLabel('Расходы за 12 месяцев, ₽', { exact: true }).fill('14000000');
  await page.getByLabel('Платежи по текущим кредитам в месяц, ₽', { exact: true }).fill('50000');
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByRole('button', { name: 'Отправить заявку', exact: true }).click();
  await expect(page).toHaveURL(/\/applications\/[0-9a-f-]{36}$/);
  const applicationUrl = page.url();
  const id = applicationUrl.split('/').at(-1)!;
  await expect(page.getByRole('heading', { name: 'Кредит одобрен', exact: true })).toBeVisible({
    timeout: 30_000,
  });
  await page.getByRole('button', { name: 'Принять предложение', exact: true }).click();
  await sign(page);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Деньги выданы', exact: true })).toBeVisible();
  expect((await api<CreditApplication>(page, `/applications/${id}`)).status).toBe('DISBURSED');
  await page.getByRole('link', { name: /^Уведомления/ }).click();
  const notification = page
    .getByRole('article')
    .filter({ has: page.locator(`a[href="/applications/${id}"]`) })
    .first();
  await expect(notification).toBeVisible();
  await notification.getByRole('button', { name: 'Отметить прочитанным', exact: true }).click();
  await expect(
    notification.getByRole('button', { name: 'Отметить прочитанным', exact: true }),
  ).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath('live-notifications.png'), fullPage: true });
});

test('live employee request, client upload, both downloads and a counter offer', async ({
  page,
}, testInfo) => {
  await login(page);
  const app = await createSubmitted(page, 'Морозов', {
    productCode: 'CREDIT_LINE',
    requestedAmount: '1000000.00',
    term: 6,
    fundingMode: 'TRANCHES',
    repaymentType: 'INTEREST_ONLY',
    financials: {
      annualRevenue: '12000000.00',
      annualExpenses: '9000000.00',
      monthlyDebtPayment: '50000.00',
    },
  });
  await awaitStatus(page, app.id, 'MANUAL_REVIEW');
  await changeRole(page, 'bank');
  await page.goto(`/backoffice/applications/${app.id}`);
  await page.getByText('Запросить дополнительные данные', { exact: true }).click();
  await page
    .getByLabel('Запрос клиенту', { exact: true })
    .fill('Пришлите отчётность для сквозной проверки.');
  await page.getByRole('button', { name: 'Запросить сведения', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Действия не требуются', exact: true }),
  ).toBeVisible();
  await changeRole(page, 'client');
  await page.goto(`/applications/${app.id}`);
  await page.getByLabel('Выручка за 12 месяцев, ₽', { exact: true }).fill('13000000');
  await page.getByLabel('Расходы за 12 месяцев, ₽', { exact: true }).fill('9000000');
  // Exceed Nginx's default 1 MiB upload limit and verify that both downloads preserve every byte.
  const content = Buffer.concat([
    Buffer.from('%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF'),
    Buffer.alloc(1024 * 1024, 32),
  ]);
  await page
    .locator('input[type=file]')
    .setInputFiles({ name: 'live-report.pdf', mimeType: 'application/pdf', buffer: content });
  await expect(
    page.getByText('✓ Финансовая отчётность: live-report.pdf', { exact: true }),
  ).toBeVisible();
  const clientDocuments = page.locator('section').filter({
    has: page.getByRole('heading', { name: 'Документы заявки', exact: true }),
  });
  await expect(
    clientDocuments.getByRole('button', { name: 'Скачать live-report.pdf', exact: true }),
  ).toBeVisible({ timeout: 5000 });
  await page.getByLabel('Комментарий для банка', { exact: true }).fill('Отчётность приложена.');
  await page.getByRole('button', { name: 'Отправить данные', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'На рассмотрении у специалиста', exact: true }),
  ).toBeVisible();
  const clientDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Скачать live-report.pdf', exact: true }).click();
  const clientFile = await clientDownload;
  expect(await readFile((await clientFile.path())!)).toEqual(content);
  await changeRole(page, 'bank');
  await page.goto(`/backoffice/applications/${app.id}`);
  const documentsPanel = page
    .getByRole('article')
    .filter({ has: page.getByRole('heading', { name: 'Документы', exact: true }) });
  const bankDownload = page.waitForEvent('download');
  await documentsPanel
    .getByRole('button', { name: 'Скачать live-report.pdf', exact: true })
    .click();
  const bankFile = await bankDownload;
  expect(await readFile((await bankFile.path())!)).toEqual(content);
  const requestsPanel = page.getByRole('article').filter({
    has: page.getByRole('heading', { name: 'Запросы сведений и ответы', exact: true }),
  });
  await expect(requestsPanel.getByText('Отчётность приложена.', { exact: false })).toBeVisible();
  await page.getByRole('combobox', { name: 'Решение сотрудника', exact: true }).click();
  await page.getByRole('option', { name: 'Предложить другие условия', exact: true }).click();
  await page.getByLabel('Сумма предложения, ₽', { exact: true }).fill('500000');
  await page
    .getByLabel('Комментарий к решению', { exact: true })
    .fill('Предлагаем лимит 500 000 рублей.');
  await page.getByRole('button', { name: 'Сохранить решение', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Действия не требуются', exact: true }),
  ).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('live-underwriter.png'), fullPage: true });
  await changeRole(page, 'client');
  await page.goto(`/applications/${app.id}`);
  await expect(page.getByText('Предлагаем лимит 500 000 рублей.', { exact: true }).first()).toBeVisible();
  await page.getByRole('button', { name: 'Принять предложение', exact: true }).click();
  await sign(page);
});

test('live preliminary offer is confirmed by the employee and signed by the client', async ({
  page,
}) => {
  await login(page);
  const app = await createSubmitted(page, 'ТехноПром', {
    requestedAmount: '5000000.00',
    term: 36,
    financials: {
      annualRevenue: '60000000.00',
      annualExpenses: '48000000.00',
      monthlyDebtPayment: '200000.00',
    },
  });
  await awaitStatus(page, app.id, 'APPROVED');
  await page.goto(`/applications/${app.id}`);
  await expect(page.getByText(/Это предварительное одобрение/)).toBeVisible();
  await page.getByRole('button', { name: 'Принять предложение', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Подтверждаем условия', exact: true }),
  ).toBeVisible();
  await changeRole(page, 'bank');
  await page.goto(`/backoffice/applications/${app.id}`);
  await page.getByRole('button', { name: 'Сохранить решение', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Действия не требуются', exact: true }),
  ).toBeVisible();
  await changeRole(page, 'client');
  await page.goto(`/applications/${app.id}`);
  await sign(page);
});

test('live system request for missing financials returns to automatic processing', async ({
  page,
}) => {
  await login(page);
  const app = await createSubmitted(page, 'Северный ветер', { financials: undefined });
  await awaitStatus(page, app.id, 'NEED_INFO');
  await page.goto(`/applications/${app.id}`);
  // История заявки повторяет запрос банка; в карточке решения он должен быть один раз
  await expect(
    page
      .locator('p:not(.applicationHistory p)')
      .getByText('Укажите в анкете: выручка за год, расходы за год, ежемесячные платежи по кредитам', { exact: true }),
  ).toHaveCount(1);
  await page.getByLabel('Выручка за 12 месяцев, ₽', { exact: true }).fill('20000000');
  await page.getByLabel('Расходы за 12 месяцев, ₽', { exact: true }).fill('14000000');
  await page.getByLabel('Платежи по кредитам в месяц, ₽', { exact: true }).fill('50000');
  await page.getByRole('button', { name: 'Отправить данные', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Кредит одобрен', exact: true })).toBeVisible({
    timeout: 30_000,
  });
});

test('live rejection reasons and advice are displayed', async ({ page }) => {
  await login(page);
  const app = await createSubmitted(page, 'Альтаир');
  await awaitStatus(page, app.id, 'REJECTED');
  await page.goto(`/applications/${app.id}`);
  await expect(
    page.getByRole('heading', { name: 'Сейчас мы не можем одобрить заявку', exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/Погасите просрочку/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Принять предложение', exact: true })).toHaveCount(
    0,
  );
});

test('live Gamma-Start application supports a daily subscription rate without financials', async ({
  page,
}) => {
  await login(page);
  await selectCompany(page, 'Соколова');
  await chooseProduct(page, 'GAMMA_START', true);
  await page.getByLabel('Сумма кредита, ₽', { exact: true }).fill('500000');
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByRole('button', { name: 'Отправить заявку', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Кредит одобрен', exact: true })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText('0,15% в день', { exact: true })).toBeVisible();
  await expect(page.getByText('22 500 ₽', { exact: true })).toBeVisible();
  await expect(page.getByText('Гамма-Старт', { exact: true }).first()).toBeVisible();
});
