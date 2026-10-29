import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: 'live-backend.spec.ts',
  outputDir: './test-results-live',
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 30_000 },
  use: {
    baseURL: 'http://127.0.0.1:5175',
    channel: process.env.PLAYWRIGHT_CHANNEL,
    trace: 'retain-on-failure',
    actionTimeout: 30_000,
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5175 --strictPort --mode live-test',
    url: 'http://127.0.0.1:5175',
    env: {
      VITE_API_MODE: 'http',
      VITE_API_BASE_URL: '/api/v1',
      API_PROXY_TARGET: process.env.API_PROXY_TARGET || 'http://localhost:8080',
    },
  },
  projects: [
    { name: 'live-desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'live-mobile', use: { ...devices['Pixel 7'] } },
  ],
});
