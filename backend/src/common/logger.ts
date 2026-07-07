import pino from 'pino';
import { config } from './config.js';

function redactHeaders(headers: Record<string, string | string[] | undefined>): Record<string, string> {
  const safe: Record<string, string> = {};
  for (const [key, val] of Object.entries(headers)) {
    const lower = key.toLowerCase();
    if (lower === 'authorization' || lower === 'cookie' || lower === 'set-cookie') {
      safe[key] = '[REDACTED]';
    } else {
      safe[key] = String(val ?? '');
    }
  }
  return safe;
}

export const logger = pino({
  level: config.nodeEnv === 'production' ? 'info' : 'debug',
  transport: config.nodeEnv !== 'production' ? { target: 'pino-pretty' } : undefined,
  serializers: {
    req: (r) => ({
      method: r.method,
      url: r.url,
      headers: r.headers ? redactHeaders(r.headers) : undefined,
    }),
    res: (r) => ({ statusCode: r.statusCode }),
    err: (e) => ({ message: e.message, stack: e.stack }),
  },
  redact: {
    paths: ['req.headers.authorization', 'req.headers.cookie', 'req.headers["set-cookie"]'],
    censor: '[REDACTED]',
  },
});
