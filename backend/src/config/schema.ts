import { z } from 'zod';

const PersistenceModeSchema = z.enum(['memory', 'dual-write', 'database-read', 'database']);

export const ConfigSchema = z
  .object({
    nodeEnv: z.enum(['development', 'production', 'test']).default('development'),
    port: z.coerce.number().int().positive().default(4000),

    serviceToken: z.string().min(1, 'SERVICE_TOKEN is required'),

    corsAllowedOrigins: z.string().default('*'),
    bodyLimit: z.coerce.number().int().positive().default(1048576),

    database: z.object({
      url: z.string().default(''),
      poolMin: z.coerce.number().int().nonnegative().default(2),
      poolMax: z.coerce.number().int().positive().default(10),
      poolAcquireTimeoutMs: z.coerce.number().int().positive().default(10000),
      poolIdleTimeoutMs: z.coerce.number().int().positive().default(30000),
      verificationIntervalMs: z.coerce.number().int().nonnegative().default(0),
      persistenceMode: PersistenceModeSchema.default('dual-write'),
    }),

    signaling: z.object({
      maxMessageSize: z.coerce.number().int().positive().default(262144),
      rateLimitMessages: z.coerce.number().int().positive().default(30),
      rateLimitWindowSec: z.coerce.number().int().positive().default(10),
      connectionRateLimit: z.coerce.number().int().positive().default(10),
    }),
  })
  .strict()
  .refine(
    (data) => {
      if (
        (data.database.persistenceMode === 'database' ||
          data.database.persistenceMode === 'database-read') &&
        !data.database.url
      ) {
        return false;
      }
      return true;
    },
    {
      message: 'DATABASE_URL is required when persistence mode is "database" or "database-read"',
      path: ['database.url'],
    },
  );

export type Config = z.infer<typeof ConfigSchema>;
