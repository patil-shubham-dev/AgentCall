import Fastify from 'fastify';
import cors from '@fastify/cors';
import rateLimit from '@fastify/rate-limit';
import compress from '@fastify/compress';
import helmet from '@fastify/helmet';
import crypto from 'node:crypto';
import { config } from './common/config.js';
import { logger } from './common/logger.js';
import { checkConnection, destroyConnection } from './db/connection.js';
import { checkRedis, redis } from './db/redis.js';
import { registerRoutes } from './routes.js';
import { createSignalingServer } from './signaling/server.js';
import type { WebSocketServer } from 'ws';

async function main() {
  // Database connections
  await checkConnection();
  await checkRedis();
  logger.info('Database connections established');

  // HTTP API server
  const app = Fastify({
    logger: {
      level: config.nodeEnv === 'production' ? 'info' : 'debug',
      serializers: {
        req: (r: { method: string; url: string; headers: Record<string, string | string[] | undefined> }) => ({
          method: r.method,
          url: r.url,
          requestId: r.headers['x-request-id'],
        }),
        res: (r: { statusCode: number }) => ({ statusCode: r.statusCode }),
        err: (e: Error) => ({ type: e.name, message: e.message, stack: e.stack ?? '' }),
      },
    },
    bodyLimit: config.security.bodyLimit,
    requestIdHeader: 'x-request-id',
    genReqId: () => crypto.randomUUID(),
  });

  // Security headers
  await app.register(helmet, {
    contentSecurityPolicy: false,
    crossOriginEmbedderPolicy: false,
  });

  // Response compression
  await app.register(compress, { global: true });

  // CORS — restrict to configured origins in production
  const corsOrigins = config.security.corsAllowedOrigins === '*'
    ? true
    : config.security.corsAllowedOrigins.split(',').map((s) => s.trim());
  await app.register(cors, {
    origin: corsOrigins,
    credentials: true,
    methods: ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS'],
    allowedHeaders: ['Content-Type', 'Authorization', 'X-Request-Id'],
    exposedHeaders: ['X-Request-Id'],
  });

  // Rate limiting — global defaults, per-route overrides in routes.ts
  await app.register(rateLimit, {
    max: 100,
    timeWindow: '1 minute',
    errorResponseBuilder: (_req, context) => ({
      error: 'RATE_LIMITED',
      message: `Too many requests. Rate limit: ${context.max} per ${context.after}`,
    }),
  });

  // Request ID propagation
  app.addHook('onRequest', async (request, reply) => {
    reply.header('X-Request-Id', request.id);
  });

  // Structured error handler
  app.setErrorHandler(async (error, request, reply) => {
    const requestId = request.id;
    const statusCode = error.statusCode ?? 500;

    // Known application errors
    const errAny = error as unknown as Record<string, unknown>;
    if (typeof errAny.code === 'string') {
      const code = errAny.code as string;
      const msg = errAny.message as string ?? 'Unknown error';
      const details = errAny.details as Record<string, unknown> | undefined;
      if (statusCode >= 500) {
        logger.error({ err: error, requestId }, msg);
      }
      return reply.status(statusCode).send({
        error: code,
        message: msg,
        request_id: requestId,
        ...(details ? { details } : {}),
      });
    }

    // Rate limit errors
    if (statusCode === 429) {
      return reply.status(429).send({
        error: 'RATE_LIMITED',
        message: error.message ?? 'Too many requests',
        request_id: requestId,
      });
    }

    // Validation errors from Fastify schema
    if ('validation' in error) {
      return reply.status(400).send({
        error: 'VALIDATION_ERROR',
        message: error.message,
        request_id: requestId,
      });
    }

    // Unhandled errors
    logger.error({ err: error, requestId }, 'Unhandled error');
    return reply.status(500).send({
      error: 'INTERNAL_ERROR',
      message: config.nodeEnv === 'production' ? 'Internal server error' : error.message,
      request_id: requestId,
    });
  });

  registerRoutes(app);

  // Start HTTP server
  let signalingServer: WebSocketServer | undefined;
  try {
    await app.ready();
    await app.listen({ port: config.port, host: '0.0.0.0' });
    logger.info({ port: config.port }, 'HTTP API server started');

    signalingServer = createSignalingServer(config.signalingPort);
  } catch (err) {
    logger.error({ err }, 'Failed to start server');
    process.exit(1);
  }

  // Graceful shutdown
  const shutdown = async (signal: string) => {
    logger.info({ signal }, 'Shutdown signal received');
    try {
      await app.close();
      signalingServer?.close();
      redis.disconnect();
      await destroyConnection();
      logger.info('Server shut down gracefully');
      process.exit(0);
    } catch (err) {
      logger.error({ err }, 'Shutdown error');
      process.exit(1);
    }
  };

  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));
}

main().catch((err) => {
  logger.error({ err }, 'Fatal startup error');
  process.exit(1);
});
