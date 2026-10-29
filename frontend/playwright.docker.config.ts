import { defineConfig, devices } from '@playwright/test';
import liveConfig from './playwright.live.config';

export default defineConfig({
  ...liveConfig,
  testMatch: ['live-backend.spec.ts', 'docker-serving.spec.ts'],
  outputDir: './test-results-docker',
  use: {
    ...liveConfig.use,
    baseURL: process.env.DOCKER_BASE_URL || 'http://127.0.0.1:5173',
  },
  // The application is already running in Compose; never start a Vite server here.
  webServer: undefined,
  projects: [
    { name: 'docker-desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'docker-mobile', use: { ...devices['Pixel 7'] } },
  ],
});
