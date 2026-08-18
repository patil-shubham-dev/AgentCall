import { randomUUID } from 'node:crypto';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { config } from '../common/config.js';
import { logger } from '../common/logger.js';
import { DEFAULT_AGENT_NAME, resolveAiKey } from '../voicebridge/ai-keys.js';
import { createTools } from './tools.js';
import { mcpIdentityStorage, type McpIdentity } from './identity.js';
import { McpSessionRegistry, type McpManagedSession } from './session-registry.js';
import type { VoiceBridgeService } from '../voicebridge/service.js';

const ALLOWED_CORS_ORIGINS = new Set([
  'https://chatgpt.com',
  'https://app.chatgpt.com',
  'https://claude.ai',
  ...config.security.corsAllowedOrigins.split(',').map((s) => s.trim()).filter(Boolean),
]);

function extractToken(request: FastifyRequest): string | null {
  const header = request.headers.authorization;
  if (header?.startsWith('Bearer ')) return header.slice(7);

  const apiKeyHeader = request.headers['x-api-key'];
  if (typeof apiKeyHeader === 'string' && apiKeyHeader.length > 0) return apiKeyHeader;

  const queryKey = (request.query as Record<string, unknown> | undefined)?.key;
  if (typeof queryKey === 'string' && queryKey.length > 0) return queryKey;

  return null;
}

async function resolveIdentity(token: string | null): Promise<McpIdentity | null> {
  if (!token) return null;
  if (token === config.serviceToken) {
    return { agentName: DEFAULT_AGENT_NAME, via: 'service' };
  }
  const aiKey = await resolveAiKey(token).catch(() => null);
  if (aiKey) {
    return { agentName: aiKey.name, keyId: aiKey.id, via: 'ai_key' };
  }
  return null;
}

interface McpSession {
  server: Server;
  transport: StreamableHTTPServerTransport;
}

function createMcpSession(voicebridge: VoiceBridgeService): McpSession {
  const server = new Server(
    { name: 'agentcall-mcp', version: '2.0.0' },
    { capabilities: { tools: {} } },
  );

  const tools = createTools(voicebridge);

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: tools.map((t) => ({
      name: t.name,
      description: t.description,
      inputSchema: t.inputSchema as Record<string, unknown>,
    })),
  }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const tool = tools.find((t) => t.name === request.params.name);
    if (!tool) {
      return {
        content: [{ type: 'text', text: `Unknown tool: ${request.params.name}` }],
        isError: true,
      };
    }
    try {
      return await tool.handler(request.params.arguments ?? {});
    } catch (err) {
      logger.error({ err, tool: request.params.name }, 'MCP tool handler error');
      return {
        content: [{ type: 'text', text: `Internal error executing ${request.params.name}` }],
        isError: true,
      };
    }
  });

  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: () => randomUUID(),
  });

  return { server, transport };
}

/** True when the JSON-RPC body is a notifications/ping (single or batch). */
function isPingNotification(body: unknown): boolean {
  if (Array.isArray(body)) {
    return body.length > 0 && body.every((m) => (m as { method?: string } | null)?.method === 'notifications/ping');
  }
  return (body as { method?: string } | null)?.method === 'notifications/ping';
}

export function registerMcpEndpoint(app: FastifyInstance, voicebridge: VoiceBridgeService): McpSessionRegistry {
  // When the LAST MCP session of an agent closes — by explicit DELETE, the
  // 30-min idle sweep, or the 45s liveness sweep (kill -9 / dropped TCP) —
  // abort that agent's open calls so a crashed/abandoned agent process never
  // leaves calls ringing or paused. cancelCallsByAgent skips calls with an
  // active ai_wait lease (the agent's waiter is still alive mid-turn), so a
  // long send_message_and_wait that outlived the idle window can't get its
  // call cancelled — EXCEPT here the dead agent's leases are force-disposed
  // first: a session whose heartbeat stopped has no live waiter, and a stale
  // lease must never shield the call from the disconnect abort.
  const sessions = new McpSessionRegistry(
    (agentName) => {
      logger.info({ agentName }, '[MCP] last session for agent closed; aborting open calls');
      void voicebridge
        .forceDisposeAiWaits(agentName)
        .then(() => voicebridge.cancelCallsByAgent(agentName, 'agent_disconnected'))
        .catch((err) => logger.error({ err, agentName }, '[MCP] cancelCallsByAgent failed after agent session close'));
    },
    (agentName) => voicebridge.hasOpenCalls(agentName),
  );
  app.decorate('mcpSessions', sessions);

  app.all('/mcp', {
    config: { rateLimit: { max: 120, timeWindow: '1 minute' } },
  }, async (request, reply) => {
    const origin = request.headers.origin;
    if (origin && ALLOWED_CORS_ORIGINS.has(origin)) {
      reply.header('Access-Control-Allow-Origin', origin);
      reply.header('Access-Control-Allow-Headers', 'Content-Type, Authorization, x-api-key, Mcp-Session-Id, mcp-session-id');
      reply.header('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
    }

    if (request.method === 'OPTIONS') {
      return reply.status(204).send();
    }

    const identity = await resolveIdentity(extractToken(request));
    if (!identity) {
      return reply.status(401).send({
        error: 'UNAUTHORIZED',
        message: 'Valid Bearer token, x-api-key, or ?key= query parameter required. ' +
          'Create an AI key in the AgentCall phone app (Settings > Add AI).',
      });
    }

    const sessionId = request.headers['mcp-session-id'] ?? request.headers['Mcp-Session-Id'];
    let session: McpManagedSession | null = null;
    if (typeof sessionId === 'string' && sessionId.length > 0) {
      const existing = sessions.get(sessionId);
      if (!existing) {
        return reply.status(404).send({ error: 'SESSION_NOT_FOUND', message: 'Unknown or expired Mcp-Session-Id. Re-initialize the session.' });
      }
      session = existing;
      // notifications/ping refreshes only the liveness clock (kill -9 / dropped
      // TCP detection); every other request is real activity as well.
      if (isPingNotification(request.body)) {
        sessions.heartbeat(sessionId);
      } else {
        sessions.touch(sessionId);
      }
    } else {
      const created = createMcpSession(voicebridge);
      let connectOk = true;
      await created.server.connect(created.transport).catch((err) => {
        connectOk = false;
        logger.error({ err }, '[MCP] connect failed');
      });
      if (!connectOk) {
        return reply.status(500).send({ error: 'INTERNAL', message: 'Failed to create MCP session.' });
      }
      session = { ...created, lastActivityAt: Date.now(), lastHeartbeatAt: Date.now() };
    }

    if (!session) {
      return reply.status(500).send({ error: 'INTERNAL', message: 'Failed to create MCP session.' });
    }
    const activeSession = session;

    logger.debug({ sessionId: activeSession.transport.sessionId, identity: identity.agentName, via: identity.via }, '[MCP] request');

    reply.hijack();
    await mcpIdentityStorage.run(identity, async () => {
      await activeSession.transport.handleRequest(request.raw, reply.raw, request.body);
    });

    if (typeof sessionId !== 'string' || sessionId.length === 0) {
      const generated = activeSession.transport.sessionId;
      if (generated) {
        sessions.set(generated, {
          server: activeSession.server,
          transport: activeSession.transport,
          lastActivityAt: Date.now(),
          lastHeartbeatAt: Date.now(),
          agentName: identity.agentName,
        });
        activeSession.transport.onclose = () => {
          sessions.delete(generated);
          logger.debug({ sessionId: generated }, '[MCP] session closed');
        };
      }
    }
  });

  const mcpSweeper = setInterval(() => {
    void sessions
      .sweepExpired(config.mcp.sessionIdleMs)
      .catch((err) => logger.error({ err }, '[MCP] idle session sweep failed'));
  }, config.mcp.sessionSweepIntervalMs);
  mcpSweeper.unref();
  logger.info(
    { idleMs: config.mcp.sessionIdleMs, intervalMs: config.mcp.sessionSweepIntervalMs },
    '[MCP] idle session sweeper started',
  );

  // Liveness sweep: closes sessions whose heartbeat stopped (kill -9 / dropped
  // TCP) when the agent still has open calls, so a hard-crashed agent's calls
  // are aborted in ~45s instead of waiting for the 30-min idle sweep.
  const livenessSweeper = setInterval(() => {
    void sessions
      .sweepDead(config.mcp.livenessTimeoutMs)
      .catch((err) => logger.error({ err }, '[MCP] liveness sweep failed'));
  }, config.mcp.livenessSweepIntervalMs);
  livenessSweeper.unref();
  logger.info(
    { timeoutMs: config.mcp.livenessTimeoutMs, intervalMs: config.mcp.livenessSweepIntervalMs },
    '[MCP] liveness sweeper started',
  );

  logger.info('[MCP] streamable HTTP endpoint registered at /mcp');
  return sessions;
}
