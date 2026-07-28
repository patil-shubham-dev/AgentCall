import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    include: ['src/**/*.test.ts'],
    env: {
      POSTGRES_PASSWORD: 'test-pg-pass',
      REDIS_PASSWORD: 'test-redis-pass',
      SERVICE_TOKEN: 'test-service-token',
      COTURN_SECRET: 'test-coturn-secret',
      NODE_ENV: 'test',
    },
    setupFiles: ['src/__tests__/setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
    },
  },
});
