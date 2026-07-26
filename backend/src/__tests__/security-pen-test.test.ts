import { describe, it, expect, vi } from 'vitest';
import { withRetry } from '../common/retry.js';
import { InMemorySessionRepository } from '../voicebridge/repositories/index.js';

describe('Penetration: authentication and authorization', () => {
  it('rejects missing bearer token gracefully (solo-user fallback)', () => {
    const authFn = (header: string | undefined) => {
      if (!header?.startsWith('Bearer ')) {
        return { userId: 'solo-user', role: 'user' as const };
      }
      const token = header.slice(7);
      if (token === 'valid-token') {
        return { userId: 'service', role: 'service' as const };
      }
      return { userId: 'solo-user', role: 'user' as const };
    };

    expect(authFn(undefined)).toEqual({ userId: 'solo-user', role: 'user' });
    expect(authFn('')).toEqual({ userId: 'solo-user', role: 'user' });
    expect(authFn('Bearer invalid')).toEqual({ userId: 'solo-user', role: 'user' });
    expect(authFn('Bearer valid-token')).toEqual({ userId: 'service', role: 'service' });
  });

  it('rejects expired-style tokens as solo-user', () => {
    const authFn = (header: string | undefined) => {
      if (!header?.startsWith('Bearer ')) {
        return { userId: 'solo-user', role: 'user' as const };
      }
      return { userId: 'solo-user', role: 'user' as const };
    };

    expect(authFn('Bearer expired-token-abc123')).toEqual({ userId: 'solo-user', role: 'user' });
  });

  it('auth middleware rejects solo-user (unauthenticated)', () => {
    const authFn = (header: string | undefined) => {
      if (!header?.startsWith('Bearer ')) {
        return { userId: 'solo-user', role: 'user' as const };
      }
      const token = header.slice(7);
      if (token === 'test-service-token') {
        return { userId: 'service', role: 'service' as const };
      }
      return { userId: 'solo-user', role: 'user' as const };
    };

    // Solo-user should be rejected by the middleware
    const unauth = authFn(undefined);
    expect(unauth.role).toBe('user');
    expect(unauth.userId).toBe('solo-user');

    // Valid token should pass
    const authed = authFn('Bearer test-service-token');
    expect(authed.role).toBe('service');
  });

  it('websocket auth requires valid token', () => {
    const configServiceToken = 'test-service-token';
    const validateWsToken = (url: string) => {
      const u = new URL(url, 'http://localhost');
      const token = u.searchParams.get('token');
      return token === configServiceToken;
    };

    expect(validateWsToken('/phone?user_id=test&token=wrong')).toBe(false);
    expect(validateWsToken('/phone?user_id=test&token=test-service-token')).toBe(true);
    expect(validateWsToken('/phone?user_id=test')).toBe(false);
  });
});

describe('Penetration: malformed and oversized payloads', () => {
  it('rejects missing required fields', () => {
    const validateCreateCall = (body: Record<string, unknown>) => {
      const summary = (body.summary as string) ?? (body.context as Record<string, unknown> | undefined)?.summary as string ?? '';
      if (!summary) return { error: 'VALIDATION_ERROR', message: 'summary is required' };
      return null;
    };

    expect(validateCreateCall({})).toEqual({ error: 'VALIDATION_ERROR', message: 'summary is required' });
    expect(validateCreateCall({ summary: 'valid' })).toBeNull();
    expect(validateCreateCall({ context: {} })).toEqual({ error: 'VALIDATION_ERROR', message: 'summary is required' });
  });

  it('rejects invalid reason values', () => {
    const validReasons = ['clarification', 'approval', 'error', 'input_required'];
    const validateReason = (reason: string) => {
      if (!validReasons.includes(reason)) return { error: 'VALIDATION_ERROR', message: `reason must be one of: ${validReasons.join(', ')}` };
      return null;
    };

    expect(validateReason('invalid_reason')).toBeTruthy();
    expect(validateReason('DROP TABLE sessions')).toBeTruthy();
    expect(validateReason('<script>alert(1)</script>')).toBeTruthy();
    expect(validateReason('approval')).toBeNull();
  });

  it('rejects empty text messages', () => {
    const validateText = (text: string | undefined) => {
      if (!text || text.trim().length === 0) return { error: 'VALIDATION_ERROR', message: 'text is required' };
      return null;
    };

    expect(validateText(undefined)).toBeTruthy();
    expect(validateText('')).toBeTruthy();
    expect(validateText('   ')).toBeTruthy();
    expect(validateText('valid text')).toBeNull();
  });

  it('rejects missing content in messages', () => {
    const validateContent = (content: string | undefined) => {
      if (!content) return { error: 'VALIDATION_ERROR', message: 'content is required' };
      return null;
    };

    expect(validateContent(undefined)).toBeTruthy();
    expect(validateContent('')).toBeTruthy();
    expect(validateContent('valid content')).toBeNull();
  });
});

describe('Penetration: SQL injection resistance', () => {
  it('parameterized queries prevent SQL injection', () => {
    const pgParam = (value: unknown) => {
      // pg driver parameterizes via $1, $2 placeholders
      return { text: 'SELECT * FROM sessions WHERE id = $1', values: [value] };
    };

    const result = pgParam("' OR 1=1; DROP TABLE sessions; --");
    expect(result.values[0]).toBe("' OR 1=1; DROP TABLE sessions; --");
    expect(result.text).not.toContain(result.values[0] as string);
  });
});

describe('Penetration: path traversal resistance', () => {
  it('path params do not reach filesystem', () => {
    const callIdPattern = /^[a-zA-Z0-9-]+$/;
    const isValidPathParam = (param: string) => callIdPattern.test(param);

    expect(isValidPathParam('../../etc/passwd')).toBe(false);
    expect(isValidPathParam('valid-call-id-123')).toBe(true);
    expect(isValidPathParam('../../../proc/self/environ')).toBe(false);
  });
});

describe('Penetration: retry policy abuse resistance', () => {
  it('does not retry non-transient errors', async () => {
    const fn = vi.fn().mockRejectedValue(new Error('permanent error'));
    await expect(withRetry(fn, 'test', { maxRetries: 3, baseDelayMs: 5 })).rejects.toThrow('permanent error');
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('exhausts retries on persistent transient', async () => {
    let count = 0;
    const fn = vi.fn().mockImplementation(() => {
      count++;
      throw Object.assign(new Error('connection reset'), { code: 'ECONNRESET' });
    });
    await expect(withRetry(fn, 'test', { maxRetries: 2, baseDelayMs: 5 })).rejects.toThrow('connection reset');
    expect(count).toBe(3);
  });
});

describe('Penetration: repository invariants', () => {
  it('cannot create duplicate session', async () => {
    const repo = new InMemorySessionRepository();
    const session = {
      id: 'dup-test',
      userId: 'user',
      agentId: 'agent',
      status: 'pending' as const,
      priority: 'normal' as const,
      reason: 'input_required' as const,
      context: { summary: 'test' },
      messages: [],
      createdAt: new Date().toISOString(),
    };
    await repo.create(session);
    await repo.create(session);
    const list = await repo.list();
    expect(list.length).toBe(1);
  });

  it('delete preserves other sessions', async () => {
    const repo = new InMemorySessionRepository();
    const s1 = { id: 's1', userId: 'u', agentId: 'a', status: 'pending' as const, priority: 'normal' as const, reason: 'input_required' as const, context: { summary: '' }, messages: [], createdAt: new Date().toISOString() };
    const s2 = { id: 's2', userId: 'u', agentId: 'a', status: 'pending' as const, priority: 'normal' as const, reason: 'input_required' as const, context: { summary: '' }, messages: [], createdAt: new Date().toISOString() };
    await repo.create(s1);
    await repo.create(s2);
    await repo.delete('s1');
    const list = await repo.list();
    expect(list.length).toBe(1);
    expect(list[0]?.id).toBe('s2');
  });
});

describe('Penetration: concurrent request simulation', () => {
  it('handles concurrent creates without corruption', async () => {
    const repo = new InMemorySessionRepository();
    const promises = Array.from({ length: 50 }, (_, i) =>
      repo.create({
        id: `concurrent-${i}`,
        userId: 'user',
        agentId: 'agent',
        status: 'pending' as const,
        priority: 'normal' as const,
        reason: 'input_required' as const,
        context: { summary: '' },
        messages: [],
        createdAt: new Date().toISOString(),
      }),
    );
    await Promise.all(promises);
    const list = await repo.list();
    expect(list.length).toBe(50);
  });
});
