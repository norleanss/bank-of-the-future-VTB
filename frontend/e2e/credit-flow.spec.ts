import { test, expect } from '@playwright/test';
import {
  login,
  chooseProduct,
  completeApplication,
  demoDecision,
  selectCompany,
  changeRole,
} from './helpers';

test('selection, persistent draft, approval, signature and disbursement', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Подобрать кредит', exact: true }).click();
  await page.getByText('Оборудование и развитие', { exact: true }).click();
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByLabel('Необходимая сумма, ₽', { exact: true }).fill('2000000.05');
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByLabel('Срок, месяцев', { exact: true }).fill('18');
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByRole('button', { name: 'Показать подходящие продукты' }).click();
  await expect(page.getByRole('heading', { name: 'Мы подобрали для вас 1 продукт' })).toBeVisible();
  const product = page
    .getByRole('article')
    .filter({ has: page.getByRole('heading', { name: 'Кредит одной суммой', exact: true }) });
  await product.getByRole('link', { name: 'Подробнее о продукте' }).click();
  await page.getByRole('button', { name: 'Оформить', exact: true }).click();
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await expect(page.getByLabel('Сумма кредита, ₽', { exact: true })).toHaveValue('2000000.05');
  await page.reload();
  await expect(page.getByLabel('Сумма кредита, ₽', { exact: true })).toHaveValue('2000000.05');
  await completeApplication(page);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Кредит одобрен', exact: true })).toBeVisible({
    timeout: 15000,
  });
  await page.getByRole('button', { name: 'Принять предложение', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Готово к подписанию', exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Получить код для подписания' }).click();
  const code = await page.getByTestId('signing-code').innerText();
  await page.getByLabel('Код подтверждения', { exact: true }).fill(code);
  await page.getByRole('button', { name: 'Подписать договор', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Деньги выданы', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Деньги выданы', exact: true })).toBeVisible();
  await page.goto('/profile');
  await page.getByRole('tab', { name: 'Завершённые', exact: true }).click();
  await expect(page.getByRole('link', { name: /CR-2026-000001/ })).toBeVisible();
});

test('company isolation, draft recovery and expiry actions', async ({ page }) => {
  await login(page);
  await chooseProduct(page);
  await page.getByLabel('Сумма кредита, ₽', { exact: true }).fill('777777');
  await selectCompany(page, 'Морозов');
  await chooseProduct(page);
  await expect(page.getByLabel('Сумма кредита, ₽', { exact: true })).not.toHaveValue('777777');
  await selectCompany(page, 'Северный ветер');
  await page.goto('/applications/new');
  await expect(page.getByLabel('Сумма кредита, ₽', { exact: true })).toHaveValue('777777');
  await completeApplication(page);
  await demoDecision(page, 'Предложение истекло');
  await expect(
    page.getByRole('heading', { name: 'Срок предложения истёк', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Принять предложение', exact: true })).toHaveCount(
    0,
  );
  await expect(page.getByRole('link', { name: 'Подать новую заявку', exact: true })).toBeVisible();
});

test('manual review, requested documents and counter offer work across roles', async ({ page }) => {
  test.setTimeout(90000);
  await login(page);
  await selectCompany(page, 'Морозов');
  await chooseProduct(page);
  await page.getByLabel('Сумма кредита, ₽', { exact: true }).fill('1000000');
  await completeApplication(page);
  const applicationUrl = page.url();
  await expect(
    page.getByRole('heading', { name: 'На рассмотрении у специалиста', exact: true }),
  ).toBeVisible({ timeout: 15000 });
  await changeRole(page, 'bank');
  await page.getByRole('link', { name: /CR-2026-000001/ }).click();
  await page.getByText('Запросить дополнительные данные', { exact: true }).click();
  await page
    .getByLabel('Запрос клиенту', { exact: true })
    .fill('Уточните выручку и расходы, приложите отчётность.');
  await page.getByRole('button', { name: 'Запросить сведения', exact: true }).click();
  await expect(page.getByText('Действия не требуются', { exact: true })).toBeVisible();
  await changeRole(page, 'client');
  await page.goto(applicationUrl);
  await page.getByLabel('Выручка за 12 месяцев, ₽', { exact: true }).fill('13000000');
  await page.getByLabel('Расходы за 12 месяцев, ₽', { exact: true }).fill('8500000');
  await page.locator('input[type=file]').setInputFiles({
    name: 'report.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('%PDF-1.4 demo'),
  });
  await expect(
    page.getByText('✓ Финансовая отчётность: report.pdf', { exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByText('✓ Финансовая отчётность: report.pdf', { exact: true }),
  ).toBeVisible();
  await page.getByLabel('Выручка за 12 месяцев, ₽', { exact: true }).fill('13000000');
  await page.getByLabel('Расходы за 12 месяцев, ₽', { exact: true }).fill('8500000');
  await page.getByRole('button', { name: 'Отправить данные', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'На рассмотрении у специалиста', exact: true }),
  ).toBeVisible({ timeout: 15000 });
  await changeRole(page, 'bank');
  await page.getByRole('link', { name: /CR-2026-000001/ }).click();
  await page.getByRole('combobox', { name: 'Решение сотрудника', exact: true }).click();
  await page.getByRole('option', { name: 'Предложить другие условия', exact: true }).click();
  await page.getByRole('combobox', { name: 'Продукт в предложении', exact: true }).click();
  await page.getByRole('option', { name: 'Гамма-Старт', exact: true }).click();
  await expect(page.getByLabel('Ставка, % в день', { exact: true })).toHaveValue('0.3');
  await page.getByRole('combobox', { name: 'Продукт в предложении', exact: true }).click();
  await page.getByRole('option', { name: 'Кредит одной суммой', exact: true }).click();
  await expect(page.getByLabel('Ставка, % годовых', { exact: true })).toHaveValue('23.5');
  await page.getByRole('combobox', { name: 'Решение сотрудника', exact: true }).click();
  await page.getByRole('option', { name: 'Предложить другие условия', exact: true }).click();
  await page.getByLabel('Сумма предложения, ₽', { exact: true }).fill('500000');
  await page
    .getByLabel('Комментарий к решению', { exact: true })
    .fill('Предлагаем уменьшенную сумму.');
  await page.getByRole('button', { name: 'Сохранить решение', exact: true }).click();
  await expect(page.getByText('Действия не требуются', { exact: true })).toBeVisible();
  await changeRole(page, 'client');
  await page.goto(applicationUrl);
  await expect(
    page.getByRole('heading', { name: 'Мы готовы предложить другие условия', exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Отказаться от предложения', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Вы отказались от предложения', exact: true }),
  ).toBeVisible();
});

test('preliminary approval is confirmed by an employee before signing', async ({ page }) => {
  test.setTimeout(60000);
  await login(page);
  await selectCompany(page, 'ТехноПром');
  await chooseProduct(page);
  await page.getByLabel('Сумма кредита, ₽', { exact: true }).fill('5000000');
  await completeApplication(page);
  const applicationUrl = page.url();
  await expect(page.getByRole('heading', { name: 'Кредит одобрен', exact: true })).toBeVisible({
    timeout: 15000,
  });
  await expect(page.getByText(/Это предварительное одобрение/)).toBeVisible();
  await page.getByRole('button', { name: 'Принять предложение', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Подтверждаем условия', exact: true }),
  ).toBeVisible();
  await changeRole(page, 'bank');
  await page.getByRole('tab', { name: 'Подтверждение условий', exact: true }).click();
  await page.getByRole('link', { name: /CR-2026-000001/ }).click();
  await page.getByRole('button', { name: 'Сохранить решение', exact: true }).click();
  await expect(page.getByText('Действия не требуются', { exact: true })).toBeVisible();
  await changeRole(page, 'client');
  await page.goto(applicationUrl);
  await expect(
    page.getByRole('heading', { name: 'Готово к подписанию', exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Получить код для подписания', exact: true }).click();
  await page
    .getByLabel('Код подтверждения', { exact: true })
    .fill(await page.getByTestId('signing-code').innerText());
  await page.getByRole('button', { name: 'Подписать договор', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Деньги выданы', exact: true })).toBeVisible();
});
