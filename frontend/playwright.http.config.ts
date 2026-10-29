import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  outputDir: './test-results-http',
  testMatch: 'http-api.spec.ts',
  fullyParallel: true,
  workers: 2,
  use: {
    baseURL: 'http://127.0.0.1:5174',
    trace: 'retain-on-failure',
    channel: process.env.PLAYWRIGHT_CHANNEL,
    actionTimeout: 10_000,
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5174 --strictPort --mode http-test',
    url: 'http://127.0.0.1:5174',
    env: { VITE_API_MODE: 'http', VITE_API_BASE_URL: '/api/v1' },
  },
  projects: [
    { name: 'http-desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'http-mobile', use: { ...devices['Pixel 7'] } },
  ],
});
