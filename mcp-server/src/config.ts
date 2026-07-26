import 'dotenv/config';

function env(name: string, fallback?: string): string {
  return process.env[name] ?? fallback ?? '';
}

function parseIntSafe(raw: string, defaultVal: string): number {
  const val = parseInt(env(raw, defaultVal), 10);
  if (isNaN(val)) {
    throw new Error(`Invalid ${raw}: ${process.env[raw] ?? defaultVal}`);
  }
  return val;
}

export const config = {
  port: parseIntSafe('MCP_SERVER_PORT', '3000'),
  backendApiUrl: env('BACKEND_API_URL', 'http://localhost:4000/api/v1'),
  serviceToken: env('SERVICE_TOKEN'),
  nodeEnv: env('NODE_ENV', 'development'),

  mcpApiKey: env('MCP_API_KEY', ''),
};

export function validateConfig(): void {
  const required = ['SERVICE_TOKEN'] as const;
  const missing = required.filter((key) => !process.env[key]);
  if (missing.length > 0) {
    throw new Error(`Missing required environment variables: ${missing.join(', ')}`);
  }
}
