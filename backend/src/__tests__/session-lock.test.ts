import { describe, it, expect } from 'vitest';
import { withSessionLock } from '../voicebridge/session-lock.js';

describe('withSessionLock', () => {
  it('executes sequential operations in order', async () => {
    const results: number[] = [];
    const p1 = withSessionLock('test-1', async () => {
      await new Promise((r) => setTimeout(r, 10));
      results.push(1);
    });
    const p2 = withSessionLock('test-1', async () => {
      results.push(2);
    });
    await Promise.all([p1, p2]);
    expect(results).toEqual([1, 2]);
  });

  it('allows independent sessions to run concurrently', async () => {
    const results: string[] = [];
    const p1 = withSessionLock('session-a', async () => {
      await new Promise((r) => setTimeout(r, 10));
      results.push('a1');
    });
    const p2 = withSessionLock('session-b', async () => {
      results.push('b1');
    });
    await Promise.all([p1, p2]);
    expect(results).toContain('a1');
    expect(results).toContain('b1');
  });

  it('chains multiple operations on the same session', async () => {
    let value = 0;
    const p1 = withSessionLock('chain', async () => { value = 1; });
    const p2 = withSessionLock('chain', async () => { value = value + 1; });
    const p3 = withSessionLock('chain', async () => { value = value * 2; });
    await Promise.all([p1, p2, p3]);
    expect(value).toBe(4);
  });

  it('propagates errors from the wrapped function', async () => {
    await expect(
      withSessionLock('error-test', async () => {
        throw new Error('test error');
      }),
    ).rejects.toThrow('test error');
  });

  it('allows new operations after a failed one', async () => {
    await withSessionLock('fail-recover', async () => {
      throw new Error('temporary');
    }).catch(() => { /* expected */ });

    const result = await withSessionLock('fail-recover', async () => 'recovered');
    expect(result).toBe('recovered');
  });
});
