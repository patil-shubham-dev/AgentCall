import 'dotenv/config';

function env(name: string, fallback?: string): string {
  return process.env[name] ?? fallback ?? '';
}

function parseIntSafe(raw: string, defaultVal?: string): number {
  const rawVal = process.env[raw];
  if (rawVal === undefined || rawVal === '') {
    if (defaultVal !== undefined) return parseIntSafe(raw, defaultVal);
    return 0;
  }
  const val = parseInt(rawVal, 10);
  if (isNaN(val)) {
    throw new Error(`Invalid ${raw}: ${rawVal}`);
  }
  return val;
}

export const config = {
  port: parseIntSafe('PORT') || parseIntSafe('MCP_SERVER_PORT', '3000'),
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
