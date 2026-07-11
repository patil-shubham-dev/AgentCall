import http from 'node:http';
import { SSEServerTransport } from '@modelcontextprotocol/sdk/server/sse.js';
import type { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { logger } from './logger.js';
import { config } from './config.js';

/**
 * Starts an HTTP server that provides SSE transport for the MCP server.
 * This allows remote AI agents (e.g., Claude Code, Cursor) to connect
 * over HTTP/HTTPS instead of stdio.
 *
 * Endpoints:
 *   GET /sse  — SSE endpoint, the agent connects here to receive events
 *   POST /message — POST endpoint, the agent sends tool requests here
 */
export async function startSSEServer(mcpServer: Server): Promise<http.Server> {
  const sessions = new Map<string, SSEServerTransport>();

  const httpServer = http.createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);

    try {
      // SSE endpoint — agents connect here to receive events
      if (req.method === 'GET' && url.pathname === '/sse') {
        const transport = new SSEServerTransport('/message', res);
        const sessionId = transport.sessionId;
        sessions.set(sessionId, transport);

        res.on('close', () => {
          sessions.delete(sessionId);
          logger.info({ sessionId }, 'SSE session closed');
        });

        await mcpServer.connect(transport);
        logger.info({ sessionId }, 'SSE session connected');
        return;
      }

      // Message endpoint — agents send tool requests here
      if (req.method === 'POST' && url.pathname === '/message') {
        const sessionId = url.searchParams.get('sessionId');
        if (!sessionId) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Missing sessionId query parameter' }));
          return;
        }

        const transport = sessions.get(sessionId);
        if (!transport) {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Session not found' }));
          return;
        }

        await transport.handlePostMessage(req, res);
        return;
      }

      // Health check
      if (req.method === 'GET' && url.pathname === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          status: 'ok',
          transport: 'sse',
          active_sessions: sessions.size,
        }));
        return;
      }

      // Default: list available transports
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        name: 'agentcall-mcp',
        transports: ['stdio', 'sse'],
        endpoints: {
          sse: '/sse',
          message: '/message',
          health: '/health',
        },
      }));
    } catch (err) {
      logger.error({ err }, 'SSE server request error');
      if (!res.headersSent) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Internal server error' }));
      }
    }
  });

  return new Promise((resolve) => {
    httpServer.listen(config.port, '0.0.0.0', () => {
      logger.info({ port: config.port }, 'MCP Server (SSE) started');
      resolve(httpServer);
    });
  });
}
