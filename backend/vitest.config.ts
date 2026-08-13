import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    include: ['src/**/*.test.ts'],
    // DB-backed suites (v2-pg-*, v2-recovery.integration) share the same
    // Postgres tables and reset them in beforeAll; parallel file workers
    // would truncate each other's rows mid-test.
    fileParallelism: false,
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
