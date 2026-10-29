import { test, expect } from '@playwright/test';
import { login, chooseProduct } from './helpers';
test('cabinet layouts fit the viewport', async ({ page }, testInfo) => {
  await login(page);
  await page.screenshot({ path: testInfo.outputPath('home.png'), fullPage: true });
  for (const route of ['/credit-products', '/credit-products/LUMP_SUM_LOAN', '/profile']) {
    await page.goto(route);
    await expect(page.locator('main h1')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  }
  await chooseProduct(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('application.png'), fullPage: true });
});
test('laptop form steps keep the next action visible without scrolling', async ({ page }) => {
  await login(page);
  const expectActionInViewport = async (height: number) => {
    const action = page.getByRole('button', { name: 'Далее', exact: true });
    await expect(action).toBeVisible();
    await expect
      .poll(async () => {
        const box = await action.boundingBox();
        return box !== null && box.y >= 0 && box.y + box.height <= height;
      })
      .toBe(true);
    expect(await page.evaluate(() => scrollY)).toBe(0);
  };
  for (const [width, height] of [
    [1366, 768],
    [1280, 650],
    [1024, 650],
  ] as const) {
    await page.setViewportSize({ width, height });
    await page.goto('/credit-selection');
    await expect(page.getByRole('heading', { name: 'Для чего вам нужны деньги?' })).toBeVisible();
    await expectActionInViewport(height);
    await chooseProduct(page);
    await expectActionInViewport(height);
    await page.getByRole('button', { name: 'Далее', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: 'Финансовые данные', exact: true }),
    ).toBeVisible();
    await expectActionInViewport(height);
  }
});
