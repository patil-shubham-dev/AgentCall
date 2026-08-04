import { logger } from '../common/logger.js';
import type { Server } from '@modelcontextprotocol/sdk/server/index.js';
import type { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';

export interface McpManagedSession {
  server: Server;
  transport: StreamableHTTPServerTransport;
  lastActivityAt: number;
  agentName?: string;
}

/**
 * In-memory registry of MCP sessions with per-session last-activity tracking.
 * MCP sessions only die via an explicit client DELETE or an idle sweep; without
 * the latter, dropped connections (the common case) leak sessions forever.
 */
export class McpSessionRegistry {
  private readonly sessions = new Map<string, McpManagedSession>();

  get(id: string): McpManagedSession | undefined {
    return this.sessions.get(id);
  }

  set(id: string, session: McpManagedSession): void {
    this.sessions.set(id, session);
  }

  delete(id: string): boolean {
    return this.sessions.delete(id);
  }

  touch(id: string): void {
    const session = this.sessions.get(id);
    if (session) session.lastActivityAt = Date.now();
  }

  count(): number {
    return this.sessions.size;
  }

  getActiveIdentities(): Set<string> {
    const active = new Set<string>();
    for (const session of this.sessions.values()) {
      if (session.agentName) {
        active.add(session.agentName);
      }
    }
    return active;
  }

  /**
   * Closes and removes every session untouched for `idleMs`. A session closed
   * this way is gone for good; the client gets SESSION_NOT_FOUND on its next
   * request and re-initializes, which is MCP's designed recovery path.
   */
  async sweepExpired(idleMs: number): Promise<number> {
    const now = Date.now();
    let closed = 0;
    for (const [id, session] of this.sessions) {
      if (now - session.lastActivityAt < idleMs) continue;
      this.sessions.delete(id);
      closed++;
      try {
        await session.server.close();
      } catch (err) {
        logger.warn({ err, sessionId: id }, '[MCP] error closing idle session');
      }
    }
    if (closed > 0) {
      logger.info({ closed, idleMs }, '[MCP] idle session sweep closed sessions');
    }
    return closed;
  }
}
