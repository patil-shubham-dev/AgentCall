import 'dotenv/config';

function env(name: string, fallback?: string): string {
  return process.env[name] ?? fallback ?? '';
}

export const config = {
  nodeEnv: env('NODE_ENV', 'development'),
  port: parseInt(env('PORT', '4000'), 10),
  signalingPort: parseInt(env('SIGNALING_PORT', '4001'), 10),

  serviceToken: env('SERVICE_TOKEN', 'dev-service-token'),

  stt: {
    model: env('STT_MODEL', 'Xenova/whisper-base'),
    enabled: env('STT_ENABLED', 'true') === 'true',
  },

  signaling: {
    maxMessageSize: parseInt(env('SIGNALING_MAX_MESSAGE_SIZE', '262144'), 10),
    rateLimitMessages: parseInt(env('SIGNALING_RATE_LIMIT_MESSAGES', '30'), 10),
    rateLimitWindowSec: parseInt(env('SIGNALING_RATE_LIMIT_WINDOW', '10'), 10),
    connectionRateLimit: parseInt(env('SIGNALING_CONNECTION_RATE_LIMIT', '10'), 10),
  },

  security: {
    corsAllowedOrigins: env('CORS_ALLOWED_ORIGINS', '*'),
    bodyLimit: parseInt(env('BODY_LIMIT_BYTES', '1048576'), 10),
  },

  backendApiUrl: env('BACKEND_API_URL', 'http://localhost:4000/api/v1'),
} as const;
