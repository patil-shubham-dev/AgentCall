import { logger } from '../common/logger.js';

const locks = new Map<string, Promise<void>>();

export function withSessionLock<T>(callId: string, fn: () => Promise<T>): Promise<T> {
  const prev = locks.get(callId) ?? Promise.resolve();
  const next = prev.then(fn, fn);
  const cleanup = next.then(
    () => { locks.delete(callId); },
    (err) => {
      locks.delete(callId);
      logger.warn({ err, callId }, '[SessionLock] operation failed, lock released');
    },
  );
  locks.set(callId, cleanup);
  return next;
}
