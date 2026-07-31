import Fastify from 'fastify';
import cors from '@fastify/cors';
import rateLimit from '@fastify/rate-limit';
import compress from '@fastify/compress';
import helmet from '@fastify/helmet';
import crypto from 'node:crypto';
import { config, validateConfig } from './common/config.js';
import { logger } from './common/logger.js';
import { MetricsCollector } from './common/metrics-collector.js';
import { DatabaseHealthMonitor } from './common/db-health-monitor.js';
import { registerRoutes } from './routes.js';
import type { RouteOptions } from './routes.js';
import { createSignalingServer } from './signaling/server.js';
import { DefaultEventBus, createEventLoggerHook } from './event-bus/index.js';
import type { EventBus } from './event-bus/index.js';
import { initializePhoneTokens } from './voicebridge/phone-tokens.js';
import { register as registerNotifications } from './voicebridge/notifications/index.js';
import { register as registerPresence } from './voicebridge/presence/index.js';
import { register as registerCalls } from './voicebridge/calls/index.js';
import { register as registerSignaling } from './voicebridge/signaling/index.js';
import { Pool } from 'pg';
import { VoiceBridgeService, isExpired, notifyPhone } from './voicebridge/service.js';
import {
  InMemorySessionRepository,
  InMemoryCallbackRepository,
  DualWriteSessionRepository,
  DualWriteCallbackRepository,
  PrimaryDatabaseSessionRepository,
  PrimaryDatabaseCallbackRepository,
  DatabaseSessionRepository,
  DatabaseCallbackRepository,
  PersistenceVerifier,
  InstrumentedSessionRepository,
  InstrumentedCallbackRepository,
} from './voicebridge/repositories/index.js';
import type { SessionRepository, CallbackRepository } from './voicebridge/repositories/index.js';
import { SessionSweeper } from './voicebridge/sweeper.js';
import { DeletionCoordinator } from './voicebridge/coordinator.js';
import { LifecycleCoordinator } from './voicebridge/lifecycle-coordinator.js';
import { RecoveryManager } from './voicebridge/recovery-manager.js';
import { CleanupScheduler } from './common/cleanup-scheduler.js';
import type { WebSocketServer } from 'ws';

const FORCE_KILL_TIMEOUT_MS = 10_000;

declare module 'fastify' {
  interface FastifyInstance {
    eventBus: EventBus;
    cleanupScheduler: CleanupScheduler;
  }
}

async function main() {
  const startupStart = Date.now();
  validateConfig();

  const metrics = new MetricsCollector();

  const eventBus = new DefaultEventBus();
  const loggerHooks = createEventLoggerHook();
  eventBus.onBeforeEvent(loggerHooks.before);
  eventBus.onAfterEvent(loggerHooks.after);
  eventBus.onError(loggerHooks.error);

  registerNotifications(eventBus);
  registerPresence(eventBus);
  registerCalls(eventBus);
  registerSignaling(eventBus);

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
    trustProxy: true,
  });

  const cleanupScheduler = new CleanupScheduler();
  app.decorate('cleanupScheduler', cleanupScheduler);

  const sessionRepo = new InMemorySessionRepository();
  const callbackRepo = new InMemoryCallbackRepository();

  let pool: Pool | undefined;
  let dbHealth: DatabaseHealthMonitor | undefined;
  let verifier: PersistenceVerifier | undefined;
  let recoveryManager: RecoveryManager | undefined;

  let sessionRepository: SessionRepository = sessionRepo;
  let callbackRepository: CallbackRepository = callbackRepo;

  const persistenceMode = config.database.persistenceMode;

  if (persistenceMode === 'database') {
    if (!config.database.url) {
      throw new Error('PERSISTENCE_MODE=database requires DATABASE_URL to be set');
    }
    pool = new Pool({
      connectionString: config.database.url,
      min: config.database.poolMin,
      max: config.database.poolMax,
      idleTimeoutMillis: config.database.poolIdleTimeoutMs,
      connectionTimeoutMillis: config.database.poolAcquireTimeoutMs,
    });
    pool.on('connect', (client) => {
      client.query("SET statement_timeout = '5s'").catch(() => {});
    });
    const dbSessionRepo = new DatabaseSessionRepository(pool);
    const dbCallbackRepo = new DatabaseCallbackRepository(pool);

    recoveryManager = new RecoveryManager(dbSessionRepo, dbCallbackRepo, sessionRepo, callbackRepo);
    await recoveryManager.loadFromDatabase();

    sessionRepository = new PrimaryDatabaseSessionRepository(dbSessionRepo);
    callbackRepository = new PrimaryDatabaseCallbackRepository(dbCallbackRepo);

    logger.info({ persistenceMode }, '[startup] production database mode enabled');
  } else if (persistenceMode === 'dual-write' || persistenceMode === 'database-read') {
    if (config.database.url) {
      pool = new Pool({
        connectionString: config.database.url,
        min: config.database.poolMin,
        max: config.database.poolMax,
        idleTimeoutMillis: config.database.poolIdleTimeoutMs,
        connectionTimeoutMillis: config.database.poolAcquireTimeoutMs,
      });
      pool.on('connect', (client) => {
        client.query("SET statement_timeout = '5s'").catch(() => {});
      });
      const dbSessionRepo = new DatabaseSessionRepository(pool);
      const dbCallbackRepo = new DatabaseCallbackRepository(pool);

      recoveryManager = new RecoveryManager(dbSessionRepo, dbCallbackRepo, sessionRepo, callbackRepo);
      await recoveryManager.loadFromDatabase();

      const readFromDb = persistenceMode === 'database-read';
      sessionRepository = new DualWriteSessionRepository(sessionRepo, dbSessionRepo, readFromDb, metrics);
      callbackRepository = new DualWriteCallbackRepository(callbackRepo, dbCallbackRepo, readFromDb, metrics);

      verifier = new PersistenceVerifier({
        memorySessionRepo: sessionRepo,
        dbSessionRepo,
        memoryCallbackRepo: callbackRepo,
        dbCallbackRepo,
        intervalMs: config.database.verificationIntervalMs > 0 ? config.database.verificationIntervalMs : undefined,
      });
      verifier.verify().catch((err) => {
        logger.error({ err }, '[PersistenceVerifier] initial check failed');
      });

      logger.info({ persistenceMode }, '[startup] database persistence enabled');
    } else {
      logger.info({ persistenceMode }, '[startup] DATABASE_URL not set, running in memory-only mode');
    }
  } else {
    logger.info({ persistenceMode }, '[startup] memory-only persistence');
  }

  // Initialize phone tokens (database-backed if a pool exists, in-memory otherwise)
  await initializePhoneTokens(pool);

  // Wrap repositories with instrumentation (timing + retry + slow-query logging)
  sessionRepository = new InstrumentedSessionRepository(sessionRepository, metrics);
  callbackRepository = new InstrumentedCallbackRepository(callbackRepository, metrics);

  const voiceBridgeService = new VoiceBridgeService(sessionRepository, callbackRepository);

  await app.register(helmet, {
    contentSecurityPolicy: {
      directives: {
        defaultSrc: ["'self'"],
        scriptSrc: ["'self'"],
        styleSrc: ["'self'", "'unsafe-inline'"],
        imgSrc: ["'self'", "data:"],
        connectSrc: ["'self'", "ws:", "wss:"],
        fontSrc: ["'self'"],
        objectSrc: ["'none'"],
        frameSrc: ["'none'"],
        upgradeInsecureRequests: [],
      },
    },
    crossOriginEmbedderPolicy: false,
  });

  await app.register(compress, { global: true });

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

  await app.register(rateLimit, {
    max: 100,
    timeWindow: '1 minute',
    errorResponseBuilder: (_req, context) => ({
      error: 'RATE_LIMITED',
      message: `Too many requests. Rate limit: ${context.max} per ${context.after}`,
    }),
  });

  app.addHook('onRequest', async (request, reply) => {
    reply.header('X-Request-Id', request.id);
  });

  app.setErrorHandler(async (error, request, reply) => {
    const requestId = request.id;
    const statusCode = error.statusCode ?? 500;
    const errAny = error as unknown as Record<string, unknown>;

    if (typeof errAny.code === 'string') {
      if (statusCode >= 500) logger.error({ err: error, requestId }, (errAny.message as string) ?? 'Error');
      return reply.status(statusCode).send({
        error: errAny.code,
        message: errAny.message ?? 'Unknown error',
        request_id: requestId,
        ...(errAny.details ? { details: errAny.details } : {}),
      });
    }

    if (statusCode === 429) {
      return reply.status(429).send({ error: 'RATE_LIMITED', message: error.message ?? 'Too many requests', request_id: requestId });
    }

    if ('validation' in error) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: error.message, request_id: requestId });
    }

    if (error instanceof SyntaxError || statusCode === 400) {
      logger.warn({ err: error, requestId }, 'Bad request body');
      return reply.status(400).send({
        error: 'INVALID_REQUEST_BODY',
        message: config.nodeEnv === 'production' ? 'Invalid request body' : error.message,
        request_id: requestId,
      });
    }

    logger.error({ err: error, requestId }, 'Unhandled error');
    return reply.status(500).send({
      error: 'INTERNAL_ERROR',
      message: config.nodeEnv === 'production' ? 'Internal server error' : error.message,
      request_id: requestId,
    });
  });

  const lifecycleCoordinator = new LifecycleCoordinator(
    cleanupScheduler,
    sessionRepository,
    callbackRepository,
    notifyPhone,
  );
  voiceBridgeService.setLifecycleCoordinator(lifecycleCoordinator);

  if (recoveryManager) {
    await recoveryManager.rebuildTimers(cleanupScheduler, lifecycleCoordinator);
  }

  // Auto-complete orphaned pending/active sessions older than the stale threshold
  // (recovered from DB) so a phone reconnect can never re-surface them.
  await voiceBridgeService.sweepStaleSessions().catch((err) => {
    logger.error({ err }, '[startup] stale-session sweep failed');
  });

  // Periodic backstop for orphaned sessions (e.g. phone offline during decline,
  // app force-stopped mid-retry): without this, a pending call survives until
  // the next restart, and AI message traffic keeps resetting its activity clock.
  setInterval(() => {
    voiceBridgeService.sweepStaleSessions().catch((err) => {
      logger.error({ err }, '[sweep] periodic stale-session sweep failed');
    });
  }, 5 * 60 * 1000);

  const deletionCoordinator = new DeletionCoordinator();

  const sessionSweeper = new SessionSweeper({
    repository: sessionRepository,
    isExpired,
    coordinator: deletionCoordinator,
    intervalMs: 5 * 60 * 1000,
  });

  if (recoveryManager) {
    sessionSweeper.sweep().catch((err) => {
      logger.error({ err }, '[startup] post-recovery sweep failed');
    });
  }

  sessionSweeper.start();

  // Database health monitor (runs if pool exists)
  if (pool) {
    dbHealth = new DatabaseHealthMonitor(pool, metrics);
    dbHealth.start();
  }

  // Register routes with all dependencies
  const routeOpts: RouteOptions = {
    voicebridge: voiceBridgeService,
    metrics,
    dbHealth,
    cleanupScheduler,
    sessionRepo: sessionRepository,
    callbackRepo: callbackRepository,
    recoveryComplete: recoveryManager !== undefined,
    startupComplete: false,
  };
  registerRoutes(app, routeOpts);

  let signalingServer: WebSocketServer | undefined;
  try {
    await app.ready();
    await app.listen({ port: config.port, host: '0.0.0.0' });
    logger.info({ port: config.port }, 'VoiceBridge HTTP API started');

    signalingServer = createSignalingServer(app.server);
    logger.info(`\n  VoiceBridge running — AI ↔ Human voice bridge`);
    logger.info(`  HTTP API:     http://localhost:${config.port}`);
    logger.info(`  Phone WS:     ws://localhost:${config.port}/phone`);
    logger.info(`  TTS engine:   Phone-side (Android TextToSpeech)\n`);

    // Record startup metrics
    routeOpts.startupComplete = true;
    metrics.recordTiming('startup.duration', Date.now() - startupStart);
    metrics.incrementCounter('startup.complete');
  } catch (err) {
    logger.error({ err }, 'Failed to start server');
    process.exit(1);
  }

  // ---------- Shutdown ----------

  let shuttingDown = false;

  const shutdown = async (signal: string): Promise<void> => {
    if (shuttingDown) return;
    shuttingDown = true;

    const shutdownStart = Date.now();
    logger.info({ signal }, 'Shutdown signal received');

    const forceKillTimer = setTimeout(() => {
      logger.error({ waited: FORCE_KILL_TIMEOUT_MS }, 'Shutdown timed out, forcing exit');
      process.exit(1);
    }, FORCE_KILL_TIMEOUT_MS);

    try {
      // Stop accepting new requests
      sessionSweeper.stop();
      dbHealth?.stop();
      verifier?.stop();
      cleanupScheduler.shutdown();

      // Wait for active operations to drain
      await app.close();
      signalingServer?.close();
      await eventBus.shutdown();

      // Flush logs
      logger.flush?.();

      // Close database pool
      if (pool) {
        await pool.end();
        logger.info('[shutdown] database pool closed');
      }

      clearTimeout(forceKillTimer);
      metrics.recordTiming('shutdown.duration', Date.now() - shutdownStart);
      logger.info({ shutdownMs: Date.now() - shutdownStart }, 'Server shut down gracefully');
      process.exit(0);
    } catch (err) {
      clearTimeout(forceKillTimer);
      logger.error({ err, shutdownMs: Date.now() - shutdownStart }, 'Shutdown error');
      process.exit(1);
    }
  };

  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));

  // Global error handlers for uncaught exceptions
  process.on('uncaughtException', (err) => {
    logger.fatal({ err, stack: err.stack }, 'Uncaught exception');
    shutdown('uncaughtException').catch(() => process.exit(1));
  });

  process.on('unhandledRejection', (reason) => {
    logger.fatal({ err: reason instanceof Error ? reason : new Error(String(reason)) }, 'Unhandled rejection');
  });
}

main().catch((err) => {
  logger.fatal({ err }, 'Fatal startup error');
  process.exit(1);
});
