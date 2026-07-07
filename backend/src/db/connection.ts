import knex from 'knex';
import { config } from '../common/config.js';
import { logger } from '../common/logger.js';

export const db = knex({
  client: 'pg',
  connection: {
    host: config.postgres.host,
    port: config.postgres.port,
    user: config.postgres.user,
    password: config.postgres.password,
    database: config.postgres.database,
  },
  pool: {
    min: 2,
    max: 10,
  },
  migrations: {
    tableName: 'knex_migrations',
    directory: './migrations',
    extension: 'ts',
  },
  log: {
    warn: (msg: string) => logger.warn(msg),
    error: (msg: string) => logger.error(msg),
    deprecate: (msg: string) => logger.warn(msg),
    debug: (msg: string) => logger.debug(msg),
  },
});

export async function checkConnection(): Promise<void> {
  try {
    await db.raw('SELECT 1');
    logger.info('PostgreSQL connected');
  } catch (err) {
    logger.error({ err }, 'PostgreSQL connection failed');
    throw err;
  }
}

export async function destroyConnection(): Promise<void> {
  await db.destroy();
}
