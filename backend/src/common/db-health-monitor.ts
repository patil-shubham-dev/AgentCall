import type { Pool } from 'pg';
import { logger } from './logger.js';
import type { MetricsCollector } from './metrics-collector.js';

export interface DatabaseHealth {
  connected: boolean;
  pingMs: number | null;
  poolTotal: number;
  poolIdle: number;
  poolWaiting: number;
  error: string | null;
}

const WARNING_THRESHOLDS = {
  pingMs: 500,
  waitingClients: 5,
  poolUtilization: 0.9,
} as const;

export class DatabaseHealthMonitor {
  private handle: NodeJS.Timeout | null = null;
  private lastPingMs: number | null = null;
  private lastError: string | null = null;
  private consecutiveFailures = 0;

  constructor(
    private pool: Pool,
    private metrics?: MetricsCollector,
    private intervalMs: number = 15000,
  ) {}

  start(): void {
    if (this.handle) return;
    this.handle = setInterval(() => this.check(), this.intervalMs);
    this.handle.unref();
    logger.info({ intervalMs: this.intervalMs }, '[DatabaseHealthMonitor] started');
  }

  stop(): void {
    if (this.handle) {
      clearInterval(this.handle);
      this.handle = null;
    }
  }

  getHealth(): DatabaseHealth {
    const poolTotal = this.pool.totalCount;
    const poolIdle = this.pool.idleCount;
    const poolWaiting = this.pool.waitingCount;

    return {
      connected: this.lastError === null,
      pingMs: this.lastPingMs,
      poolTotal,
      poolIdle,
      poolWaiting,
      error: this.lastError,
    };
  }

  private async check(): Promise<void> {
    const start = Date.now();
    try {
      const client = await this.pool.connect();
      try {
        await client.query('SELECT 1');
      } finally {
        client.release();
      }
      this.lastPingMs = Date.now() - start;
      this.lastError = null;
      this.consecutiveFailures = 0;

      this.metrics?.recordTiming('db.ping', this.lastPingMs);
    } catch (err) {
      this.lastPingMs = Date.now() - start;
      this.lastError = err instanceof Error ? err.message : String(err);
      this.consecutiveFailures++;
      logger.error(
        { err, consecutiveFailures: this.consecutiveFailures },
        '[DatabaseHealthMonitor] ping failed',
      );
    }

    this.checkPoolHealth();
  }

  private checkPoolHealth(): void {
    const total = this.pool.totalCount;
    const idle = this.pool.idleCount;
    const waiting = this.pool.waitingCount;

    this.metrics?.setGauge('db.pool.total', total);
    this.metrics?.setGauge('db.pool.idle', idle);
    this.metrics?.setGauge('db.pool.waiting', waiting);

    if (total > 0) {
      const utilization = (total - idle) / total;
      if (utilization > WARNING_THRESHOLDS.poolUtilization) {
        logger.warn(
          { utilization: Math.round(utilization * 100), total, idle, waiting },
          '[DatabaseHealthMonitor] pool utilization high',
        );
      }
    }

    if (waiting > WARNING_THRESHOLDS.waitingClients) {
      logger.warn(
        { waiting },
        '[DatabaseHealthMonitor] waiting clients threshold exceeded',
      );
    }

    if (this.lastPingMs !== null && this.lastPingMs > WARNING_THRESHOLDS.pingMs) {
      logger.warn(
        { pingMs: this.lastPingMs },
        '[DatabaseHealthMonitor] ping latency threshold exceeded',
      );
    }
  }
}
