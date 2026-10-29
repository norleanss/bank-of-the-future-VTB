import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '@playwright/test';
import { login, chooseProduct } from './helpers';
test('forms, company picker and cabinet screens pass accessibility checks', async ({ page }) => {
  const audit = async () => {
    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
      .analyze();
    expect(result.violations).toEqual([]);
  };
  await page.goto('/login');
  await expect(page.getByRole('button', { name: 'Войти как клиент', exact: true })).toBeVisible();
  await audit();
  await login(page);
  await audit();
  for (const route of ['/credit-products', '/credit-selection', '/profile']) {
    await page.goto(route);
    await expect(page.locator('main h1')).toBeVisible();
    await audit();
  }
  await chooseProduct(page);
  await expect(page.getByRole('combobox', { name: 'Цель кредита', exact: true })).toBeVisible();
  await audit();
});
test('keyboard focus and narrow layouts retain navigation and actions', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('button', { name: 'Войти как клиент', exact: true })).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Войти как клиент', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Здорово, Иван!' })).toBeVisible();
  for (const width of [320, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Подобрать кредит', exact: true })).toBeVisible();
  }
  await page.getByRole('link', { name: 'Подобрать кредит', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Для чего вам нужны деньги?' })).toBeFocused();
  await page.getByRole('radio', { name: 'Оборудование и развитие', exact: true }).focus();
  await page.keyboard.press('Space');
  await expect(
    page.getByRole('radio', { name: 'Оборудование и развитие', exact: true }),
  ).toBeChecked();
  await page.getByRole('button', { name: 'Далее', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Какая сумма нужна бизнесу?' })).toBeFocused();
});
