import { z } from 'zod';
import { ValidationError } from './errors.js';

export function validate<T>(schema: z.ZodSchema<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) {
    throw new ValidationError({
      issues: result.error.issues.map((i) => ({
        path: i.path.join('.'),
        message: i.message,
      })),
    });
  }
  return result.data;
}

export const uuidSchema = z.string().uuid();

export const createCallSchema = z.object({
  user_id: uuidSchema,
  agent_id: uuidSchema,
  context: z.object({
    task_id: z.string().optional(),
    reason: z.enum(['clarification', 'approval', 'error', 'input_required']),
    summary: z.string().min(1).max(1000),
    options: z.array(z.string()).max(10).optional(),
  }),
  priority: z.enum(['low', 'normal', 'high', 'urgent']).default('normal'),
  timeout_seconds: z.number().int().min(10).max(300).default(30),
});

export const cancelCallSchema = z.object({
  reason: z.enum(['resolved', 'timeout', 'error', 'user_requested']).default('resolved'),
});

export const deviceRegisterSchema = z.object({
  platform: z.enum(['android', 'ios', 'web']),
  push_token: z.string().optional(),
  device_name: z.string().max(255).optional(),
  app_version: z.string().optional(),
});

export const presenceQuerySchema = z.object({
  user_id: uuidSchema,
});

export const notifySchema = z.object({
  user_id: uuidSchema,
  type: z.enum(['call_incoming', 'task_complete', 'call_missed']),
  payload: z.record(z.unknown()),
});
