import { describe, it, expect } from 'vitest';
import { assertNoDevTokenInProduction, DEV_SERVICE_TOKEN } from '../common/config.js';

describe('assertNoDevTokenInProduction', () => {
  it('refuses to start when the dev token is set with NODE_ENV=production', () => {
    expect(() => assertNoDevTokenInProduction('production', DEV_SERVICE_TOKEN)).toThrow(
      /Refusing to start/,
    );
  });

  it('allows production with a real service token', () => {
    expect(() => assertNoDevTokenInProduction('production', 'a-real-token')).not.toThrow();
  });

  it('allows the dev token outside production (local dev is the intended use)', () => {
    expect(() => assertNoDevTokenInProduction('development', DEV_SERVICE_TOKEN)).not.toThrow();
    expect(() => assertNoDevTokenInProduction('test', DEV_SERVICE_TOKEN)).not.toThrow();
  });

  it('allows production with an empty token only if the required-env check catches it first', () => {
    // validateConfig rejects missing SERVICE_TOKEN before this guard ever runs;
    // the guard itself only cares about the dangerous dev value
    expect(() => assertNoDevTokenInProduction('production', '')).not.toThrow();
  });
});