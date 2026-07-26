import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import { logger } from './logger.js';
import { tools } from './tools.js';
import { startSSEServer } from './sse.js';
import { validateConfig } from './config.js';

/**
 * Creates an MCP Server instance with the tool handlers configured.
 * Each transport needs its own Server instance because `server.connect()`
 * sets a single active transport.
 */
function createConfiguredServer(): Server {
  const server = new Server(
    { name: 'agentcall-mcp', version: '0.1.0' },
    { capabilities: { tools: {} } },
  );

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
      logger.error({ err, tool: request.params.name }, 'Tool handler error');
      return {
        content: [{ type: 'text', text: `Internal error executing ${request.params.name}` }],
        isError: true,
      };
    }
  });

  return server;
}

async function main() {
  validateConfig();
  const transportType = process.env.MCP_TRANSPORT ?? 'stdio';

  if (transportType === 'sse' || transportType === 'both') {
    const sseServer = createConfiguredServer();
    await startSSEServer(sseServer);
    logger.info('MCP Server (SSE) started');
  }

  if (transportType === 'stdio' || transportType === 'both') {
    const stdioServer = createConfiguredServer();
    const transport = new StdioServerTransport();
    await stdioServer.connect(transport);
    logger.info('MCP Server (stdio) started');
  }

  // Fallback for unrecognised transport type
  if (transportType !== 'sse' && transportType !== 'both' && transportType !== 'stdio') {
    logger.warn({ transportType }, 'Unrecognised MCP_TRANSPORT, falling back to stdio-only');
    const fallbackServer = createConfiguredServer();
    const transport = new StdioServerTransport();
    await fallbackServer.connect(transport);
    logger.info('MCP Server (stdio) started (fallback)');
  }

  logger.info({ transport: transportType }, 'MCP Server ready');
}

main().catch((err) => {
  logger.error({ err }, 'MCP Server failed');
  process.exit(1);
});
