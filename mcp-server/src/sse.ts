import http from 'node:http';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { SSEServerTransport } from '@modelcontextprotocol/sdk/server/sse.js';
import { logger } from './logger.js';
import { config } from './config.js';

const transports = new Map<string, SSEServerTransport>();

export async function startSSEServer(server: Server): Promise<void> {
  const httpServer = http.createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', `http://localhost:${config.port}`);

    if (url.pathname === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'ok', server: 'agentcall-mcp' }));
      return;
    }

    if (url.pathname === '/sse') {
      const transport = new SSEServerTransport('/message', res);
      const sessionId = transport.sessionId;
      transports.set(sessionId, transport);
      res.on('close', () => {
        transports.delete(sessionId);
        logger.info({ sessionId }, 'SSE session closed');
      });
      await server.connect(transport);
      return;
    }

    if (url.pathname === '/message' && req.method === 'POST') {
      const sessionId = url.searchParams.get('sessionId');
      if (!sessionId || !transports.has(sessionId)) {
        res.writeHead(400);
        res.end('Missing or invalid sessionId');
        return;
      }
      const transport = transports.get(sessionId)!;
      await transport.handlePostMessage(req, res);
      return;
    }

    res.writeHead(404);
    res.end('Not found');
  });

  return new Promise((resolve, reject) => {
    httpServer.listen(config.port, '0.0.0.0', () => {
      logger.info({ port: config.port }, 'SSE HTTP server listening');
      resolve();
    });
    httpServer.on('error', reject);
  });
}
