import type { Knex } from 'knex';
import { config } from '../common/config.js';

const knexConfig: Knex.Config = {
  client: 'pg',
  connection: {
    host: config.postgres.host,
    port: config.postgres.port,
    user: config.postgres.user,
    password: config.postgres.password,
    database: config.postgres.database,
  },
  pool: { min: 2, max: 10 },
  migrations: {
    tableName: 'knex_migrations',
    directory: './migrations',
    extension: 'ts',
  },
};

export default knexConfig;
