import { test, expect } from '@playwright/test';
import { login, selectCompany, chooseProduct, completeApplication } from './helpers';
test('catalog retains daily rates and Gamma-Start allows no financials', async ({ page }) => {
  await login(page);
  await selectCompany(page, 'Соколова');
  await page.goto('/credit-products');
  await expect(page.getByRole('article')).toHaveCount(4);
  const start = page
    .getByRole('article')
    .filter({ has: page.getByRole('heading', { name: 'Гамма-Старт', exact: true }) });
  await expect(start).toContainText('0,3% в день');
  await expect(start).toContainText('0,15% в день');
  await chooseProduct(page, 'GAMMA_START', true);
  await page.getByLabel('Сумма кредита, ₽', { exact: true }).fill('500000');
  await completeApplication(page, true);
  await expect(page.getByRole('heading', { name: 'Кредит одобрен', exact: true })).toBeVisible({
    timeout: 15000,
  });
  await expect(page.getByText('0,15% в день', { exact: true })).toBeVisible();
});
test('75-day overdraft enables only subscription and shows the fee separately', async ({
  page,
}) => {
  await login(page);
  await page.goto('/credit-selection');
  await page.getByText('Кассовый разрыв', { exact: true }).click();
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByLabel('Необходимая сумма, ₽', { exact: true }).fill('1500000');
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByText('Дни', { exact: true }).click();
  await page.getByLabel('Срок, дней', { exact: true }).fill('75');
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await page.getByText('По мере необходимости', { exact: true }).click();
  await page.getByRole('button', { name: 'Показать подходящие продукты' }).click();
  await expect(page.getByRole('article').getByRole('heading', { level: 2 })).toHaveText([
    'Овердрафт для бизнеса',
    'Возобновляемая кредитная линия',
    'Кредит одной суммой',
    'Гамма-Старт',
  ]);
  const overdraft = page
    .getByRole('article')
    .filter({ has: page.getByRole('heading', { name: 'Овердрафт для бизнеса', exact: true }) });
  await expect(overdraft.getByRole('button', { name: 'Оформить', exact: true })).toBeDisabled();
  await overdraft.getByRole('button', { name: 'Оформить с подпиской', exact: true }).click();
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await expect(page.getByLabel('Срок, дней', { exact: true })).toHaveValue('75');
  await completeApplication(page);
  await expect(page.getByRole('heading', { name: 'Кредит одобрен', exact: true })).toBeVisible({
    timeout: 15000,
  });
  await expect(page.getByText('Комиссия за подписку при выдаче', { exact: true })).toBeVisible();
  await expect(page.getByText(/15.000 ₽ · отдельно/)).toBeVisible();
});
