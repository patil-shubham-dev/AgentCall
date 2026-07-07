import 'dotenv/config';

function env(name: string, fallback?: string): string {
  const val = process.env[name] ?? fallback;
  if (!val) throw new Error(`Missing required env var: ${name}`);
  return val;
}

export const config = {
  port: parseInt(env('MCP_SERVER_PORT', '3000'), 10),
  backendApiUrl: env('BACKEND_API_URL', 'http://localhost:4000/api/v1'),
  serviceToken: env('SERVICE_TOKEN'),
  nodeEnv: env('NODE_ENV', 'development'),
};
