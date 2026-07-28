import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { logger } from './logger.js';
import { config } from './config.js';

/** List of origins allowed for CORS. */
const ALLOWED_ORIGINS = [
  'https://chatgpt.com',
  'https://claude.ai',
  'http://localhost:3000',
  'http://localhost:3001',
  'http://localhost:4000',
];

/**
 * Check if the request includes a valid API key (via header or URL path).
 * Supports two methods:
 *   - x-api-key header (used by Claude Desktop, Claude Code, curl, etc.)
 *   - URL path segment (used by ChatGPT Developer Mode, which doesn't support custom headers)
 * If MCP_API_KEY is not set (empty string), auth is skipped (dev mode).
 */
function checkApiKey(req: http.IncomingMessage): boolean {
  if (!config.mcpApiKey) return true;

  // 1. Check x-api-key header (Claude, curl, etc.)
  const headerKey = req.headers['x-api-key'] as string | undefined;
  if (headerKey === config.mcpApiKey) return true;

  // 2. Check URL path segments for embedded key
  //    ChatGPT connector URL format: https://.../mcp/<API_KEY>
  const url = new URL(req.url ?? '/', 'http://localhost');
  const segments = url.pathname.split('/').filter(Boolean);
  if (segments.some((s) => s === config.mcpApiKey)) return true;

  return false;
}

function setCorsHeaders(res: http.ServerResponse, origin: string): void {
  const allowed = ALLOWED_ORIGINS.includes(origin) ? origin : (ALLOWED_ORIGINS[0] ?? '*');
  res.setHeader('Access-Control-Allow-Origin', allowed);
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, x-api-key');
  res.setHeader('Access-Control-Max-Age', '86400');
}

export async function startSSEServer(server: Server): Promise<void> {
  // Create a single StreamableHTTPServerTransport instance in stateful mode.
  // This is the modern MCP transport that supports both:
  //   - ChatGPT / Streamable HTTP: POST requests with JSON-RPC payloads, no session needed
  //   - Claude / SSE:  GET establishes SSE stream, POST sends messages with sessionId
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: () => randomUUID(),
  });

  // Connect the transport to the MCP server FIRST.
  // Note: server.connect() internally overwrites transport.onmessage/onerror/onclose,
  // so any callbacks set before connect() would be dead code.
  await server.connect(transport);

  const httpServer = http.createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', `http://localhost:${config.port}`);
    const origin = req.headers.origin ?? '';
    const method = req.method ?? 'GET';

    // Handle CORS preflight
    if (method === 'OPTIONS') {
      setCorsHeaders(res, origin);
      res.writeHead(204);
      res.end();
      return;
    }

    // Set CORS headers on all responses
    setCorsHeaders(res, origin);

    // Health and discovery endpoint
    if (url.pathname === '/health' || (url.pathname === '/' && method === 'GET')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        status: 'ok',
        server: 'agentcall-mcp',
        auth_required: !!config.mcpApiKey,
        transport: 'streamable-http',
        endpoints: {
          chatgpt: 'POST /mcp/<API_KEY> (ChatGPT Developer Mode connector URL)',
          claude: 'POST / (with x-api-key header) or GET /sse (SSE stream)',
          health: 'GET /health',
        },
      }));
      return;
    }

    // All other routes require API key auth
    if (!checkApiKey(req)) {
      res.writeHead(401, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        error: 'UNAUTHORIZED',
        message: 'Invalid or missing API key.',
        auth_methods: [
          'x-api-key header (Claude Desktop, Claude Code, curl)',
          'URL path segment: https://host/mcp/<API_KEY> (ChatGPT Developer Mode)',
        ],
      }));
      return;
    }

    try {
      // Delegate to the StreamableHTTPServerTransport which handles:
      // - GET requests: SSE streaming for long-lived connections (Claude-style)
      // - POST requests: JSON-RPC handling (ChatGPT-style Streamable HTTP)
      // - Session management: creates/reuses sessions based on session ID
      await transport.handleRequest(req, res);
    } catch (err) {
      logger.error({ err }, 'Error handling Streamable HTTP request');
      if (!res.headersSent) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'INTERNAL_ERROR', message: 'Internal server error' }));
      }
    }
  });

  return new Promise((resolve, reject) => {
    httpServer.listen(config.port, '0.0.0.0', () => {
      logger.info({ port: config.port, transport: 'streamable-http' }, 'MCP HTTP server listening');
      resolve();
    });
    httpServer.on('error', reject);
  });
}
