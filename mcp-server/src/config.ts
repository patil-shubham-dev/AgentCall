import 'dotenv/config';

function env(name: string, fallback?: string): string {
  return process.env[name] ?? fallback ?? '';
}

export const config = {
  port: parseInt(env('MCP_SERVER_PORT', '3000'), 10),
  backendApiUrl: env('BACKEND_API_URL', 'http://localhost:4000/api/v1'),
  serviceToken: env('SERVICE_TOKEN', 'dev-service-token'),
  nodeEnv: env('NODE_ENV', 'development'),

  // API key for ChatGPT / external AI clients to authenticate
  mcpApiKey: env('MCP_API_KEY', ''),
};
