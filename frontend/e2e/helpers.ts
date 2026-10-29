import { expect, type Page } from '@playwright/test';
export async function login(page: Page, role: 'client' | 'bank' = 'client') {
  await page.goto('/login');
  await page
    .getByRole('button', {
      name: role === 'client' ? 'Войти как клиент' : 'Войти как сотрудник банка',
      exact: true,
    })
    .click();
  await expect(
    page.getByRole('heading', {
      name: role === 'client' ? 'Здорово, Иван!' : 'Очередь заявок',
      exact: true,
    }),
  ).toBeVisible();
}
export async function changeRole(page: Page, role: 'client' | 'bank') {
  await page.getByRole('button', { name: 'Выйти', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  await login(page, role);
}
export async function selectCompany(page: Page, name: string) {
  await page.getByRole('combobox', { name: 'Демо-режим: выберите компанию', exact: true }).click();
  await page.getByRole('option', { name: new RegExp(name) }).click();
  await expect(page.getByRole('heading', { name: 'Здорово, Иван!' })).toBeVisible();
}
export async function chooseProduct(page: Page, code = 'LUMP_SUM_LOAN', subscription = false) {
  await page.goto('/credit-products/' + code);
  await page
    .getByRole('button', { name: subscription ? 'Оформить с подпиской' : 'Оформить', exact: true })
    .click();
  await expect(page.getByLabel('Название организации', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Параметры кредита', exact: true })).toBeVisible();
}
export async function completeApplication(page: Page, skipFinancials = false) {
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Финансовые данные', exact: true })).toBeVisible();
  if (!skipFinancials) {
    await page.getByLabel('Выручка за 12 месяцев, ₽', { exact: true }).fill('12000000');
    await page.getByLabel('Расходы за 12 месяцев, ₽', { exact: true }).fill('8000000');
    await page
      .getByLabel('Оборот по счёту в Гамма-Банке в месяц, ₽', { exact: true })
      .fill('1000000');
    await page.getByLabel('Платежи по текущим кредитам в месяц, ₽', { exact: true }).fill('0');
  }
  await page.getByRole('button', { name: 'Далее', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Всё верно? Проверьте заявку' })).toBeVisible();
  await page.getByRole('button', { name: 'Отправить заявку', exact: true }).click();
  await expect(page).toHaveURL(/\/applications\/[0-9a-f]{8}-[0-9a-f-]+$/);
}
export async function demoDecision(page: Page, label: string) {
  const details = page
    .locator('details')
    .filter({ has: page.getByText('Демонстрация состояний', { exact: true }) });
  if ((await details.getAttribute('open')) === null)
    await page.getByText('Демонстрация состояний', { exact: true }).click();
  await page.getByRole('combobox', { name: 'Сценарий решения', exact: true }).click();
  await page.getByRole('option', { name: label, exact: true }).click();
  await page.getByRole('button', { name: 'Показать результат' }).click();
}
