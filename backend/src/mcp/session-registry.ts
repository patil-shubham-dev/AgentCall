import { logger } from '../common/logger.js';
import type { Server } from '@modelcontextprotocol/sdk/server/index.js';
import type { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import type { ClientInfo } from '../voicebridge/types.js';

export interface McpManagedSession {
  server: Server;
  transport: StreamableHTTPServerTransport;
  lastActivityAt: number;
  /** Touched by notifications/ping only — distinct from lastActivityAt so a
   *  ping loop can't keep the 30-min idle sweep from firing. The liveness
   *  sweep keys off this to detect kill -9 / dropped TCP within ~45s. */
  lastHeartbeatAt: number;
  agentName?: string;
  /** Captured from the initialize handshake; surfaces as the caller badge. */
  clientInfo?: ClientInfo;
}

/**
 * Why an agent's last session closed. Transport liveness is not call
 * lifecycle: only 'explicit-delete' (the client deliberately closed its
 * session via DELETE /mcp) may terminate calls. Both sweep causes only drop
 * the live-presence signal — the session is already gone from the registry,
 * so presence-derived checks flip on their own. Calls then end only via the
 * call-level sweeps (PENDING_CALL_TTL_MS / STALE_ACTIVE_THRESHOLD_MS).
 */
export type AgentGoneCause = 'explicit-delete' | 'liveness-timeout' | 'idle-timeout';

/**
 * In-memory registry of MCP sessions with per-session last-activity tracking.
 * MCP sessions only die via an explicit client DELETE or a sweep; without
 * those, dropped connections (the common case) leak sessions forever.
 *
 * [onAgentGone] fires when the LAST live session of an agent closes, with the
 * cause distinguishing the trigger path: 'explicit-delete' (client DELETE —
 * deliberate disconnect, may terminate calls), 'liveness-timeout' /
 * 'idle-timeout' (sweeps — presence-only, must never terminate calls). It
 * deliberately does NOT fire while another session of the same agent remains
 * — a single session churn must never cancel calls of a still-connected agent.
 *
 * [hasOpenCalls] (optional) gates the liveness sweep: a session whose
 * heartbeat stopped is only closed when its agent still has pending/active/
 * paused calls to protect — otherwise the normal 30-min idle sweep handles it
 * and the liveness sweep stays silent (no urgency, no wakeups).
 */
export class McpSessionRegistry {
  private readonly sessions = new Map<string, McpManagedSession>();

  constructor(
    private readonly onAgentGone?: (agentName: string, cause: AgentGoneCause) => void,
    private readonly hasOpenCalls?: (agentName: string) => boolean | Promise<boolean>,
  ) {}

  get(id: string): McpManagedSession | undefined {
    return this.sessions.get(id);
  }

  set(id: string, session: McpManagedSession): void {
    this.sessions.set(id, session);
  }

  /**
   * Explicit client close (transport onclose after DELETE /mcp). This is the
   * deliberate-disconnect path — the only one that may terminate calls.
   */
  delete(id: string): boolean {
    const removed = this.sessions.get(id);
    const ok = this.sessions.delete(id);
    if (ok && removed?.agentName) {
      this.notifyIfLastGone(removed.agentName, 'explicit-delete');
    }
    return ok;
  }

  /** Fires [onAgentGone] when no live session still carries [agentName]. */
  private notifyIfLastGone(agentName: string, cause: AgentGoneCause): void {
    for (const session of this.sessions.values()) {
      if (session.agentName === agentName) return;
    }
    this.onAgentGone?.(agentName, cause);
  }

  /** Any real request (tool call, initialize, ping, ...) marks the client alive. */
  touch(id: string): void {
    const session = this.sessions.get(id);
    if (session) {
      session.lastActivityAt = Date.now();
      session.lastHeartbeatAt = Date.now();
    }
  }

  /** notifications/ping — refreshes only the liveness clock, not activity. */
  heartbeat(id: string): void {
    const session = this.sessions.get(id);
    if (session) session.lastHeartbeatAt = Date.now();
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
   * Presence snapshot for a single agent: online iff a live session carries
   * the name, lastSeenAt = most recent activity across those sessions.
   */
  getAgentStatus(agentName: string): { online: boolean; lastSeenAt: string | null } {
    let lastSeenMs = 0;
    let found = false;
    for (const session of this.sessions.values()) {
      if (session.agentName !== agentName) continue;
      found = true;
      if (session.lastActivityAt > lastSeenMs) lastSeenMs = session.lastActivityAt;
    }
    return { online: found, lastSeenAt: found ? new Date(lastSeenMs).toISOString() : null };
  }

  /**
   * Closes sessions whose heartbeat stopped more than `timeoutMs` ago AND whose
   * agent still has open calls (kill -9 / dropped TCP — the cases the 30-min
   * idle sweep and onclose both miss). A dead session with no open calls is
   * left to the normal idle sweep: aborting nothing is a no-op, and not
   * closing keeps the registry quiet for agents that just stopped pinging
   * after finishing their work.
   */
  async sweepDead(timeoutMs: number): Promise<number> {
    const now = Date.now();
    let closed = 0;
    for (const [id, session] of this.sessions) {
      if (now - session.lastHeartbeatAt < timeoutMs) continue;
      if (session.agentName && this.hasOpenCalls) {
        let open = false;
        try {
          open = await this.hasOpenCalls(session.agentName);
        } catch (err) {
          logger.warn({ err, agentName: session.agentName }, '[MCP] hasOpenCalls check failed; treating as no open calls');
        }
        if (!open) continue;
      }
      this.sessions.delete(id);
      closed++;
      try {
        await session.server.close();
      } catch (err) {
        logger.warn({ err, sessionId: id }, '[MCP] error closing dead session');
      }
      if (session.agentName) {
        this.notifyIfLastGone(session.agentName, 'liveness-timeout');
      }
    }
    if (closed > 0) {
      logger.info({ closed, timeoutMs }, '[MCP] liveness sweep closed dead sessions');
    }
    return closed;
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
      // The session is gone from the registry, so an agent whose last session
      // this was must be told (closing the transport already happened above;
      // firing before server.close() would let a not-yet-closed session count
      // as live).
      if (session.agentName) {
        this.notifyIfLastGone(session.agentName, 'idle-timeout');
      }
    }
    if (closed > 0) {
      logger.info({ closed, idleMs }, '[MCP] idle session sweep closed sessions');
    }
    return closed;
  }
}
