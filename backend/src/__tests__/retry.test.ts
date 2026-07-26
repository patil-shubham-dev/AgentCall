import { describe, it, expect, vi } from 'vitest';
import { withRetry } from '../common/retry.js';

describe('retry policy', () => {
  it('succeeds on first attempt', async () => {
    const fn = vi.fn().mockResolvedValue('ok');
    const result = await withRetry(fn, 'test');
    expect(result).toBe('ok');
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('retries on transient error and succeeds', async () => {
    const fn = vi.fn()
      .mockRejectedValueOnce(Object.assign(new Error('connection reset'), { code: 'ECONNRESET' }))
      .mockResolvedValueOnce('recovered');
    const result = await withRetry(fn, 'test');
    expect(result).toBe('recovered');
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('does not retry validation errors', async () => {
    const err = Object.assign(new Error('unique violation'), { code: '23505' });
    const fn = vi.fn().mockRejectedValue(err);
    await expect(withRetry(fn, 'test')).rejects.toThrow('unique violation');
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('does not retry syntax errors', async () => {
    const err = Object.assign(new Error('syntax error'), { code: '42601' });
    const fn = vi.fn().mockRejectedValue(err);
    await expect(withRetry(fn, 'test')).rejects.toThrow('syntax error');
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('exhausts retries on persistent transient errors', async () => {
    const fn = vi.fn().mockRejectedValue(
      Object.assign(new Error('connection timeout'), { code: 'ETIMEDOUT' }),
    );
    await expect(withRetry(fn, 'test', { maxRetries: 2, baseDelayMs: 5 })).rejects.toThrow('connection timeout');
    expect(fn).toHaveBeenCalledTimes(3);
  });

  it('respects max retries = 0', async () => {
    const fn = vi.fn().mockRejectedValue(
      Object.assign(new Error('timeout'), { code: 'ETIMEDOUT' }),
    );
    await expect(withRetry(fn, 'test', { maxRetries: 0, baseDelayMs: 5 })).rejects.toThrow('timeout');
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('detects transient errors by message pattern', async () => {
    const fn = vi.fn()
      .mockRejectedValueOnce(new Error('terminating connection due to administrator'))
      .mockResolvedValueOnce('ok');
    const result = await withRetry(fn, 'test', { maxRetries: 1, baseDelayMs: 5 });
    expect(result).toBe('ok');
    expect(fn).toHaveBeenCalledTimes(2);
  });
});
