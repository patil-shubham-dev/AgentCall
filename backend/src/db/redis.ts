import { Redis } from 'ioredis';
import { config } from '../common/config.js';
import { logger } from '../common/logger.js';

export const redis = new Redis({
  host: config.redis.host,
  port: config.redis.port,
  password: config.redis.password || undefined,
  retryStrategy: (times) => Math.min(times * 100, 5000),
  lazyConnect: true,
});

redis.on('connect', () => logger.info('Redis connected'));
redis.on('error', (err) => logger.error({ err }, 'Redis error'));

export async function checkRedis(): Promise<void> {
  await redis.connect();
  await redis.ping();
}
