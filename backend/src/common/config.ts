import 'dotenv/config';

function required(name: string): string {
  const val = process.env[name];
  if (!val) throw new Error(`Missing required env var: ${name}`);
  return val;
}

function optional(name: string, fallback: string): string {
  return process.env[name] ?? fallback;
}

export const config = {
  nodeEnv: optional('NODE_ENV', 'development'),
  port: parseInt(optional('PORT', '4000'), 10),
  signalingPort: parseInt(optional('SIGNALING_PORT', '4001'), 10),

  postgres: {
    host: optional('POSTGRES_HOST', 'localhost'),
    port: parseInt(optional('POSTGRES_PORT', '5432'), 10),
    user: optional('POSTGRES_USER', 'ic_user'),
    password: required('POSTGRES_PASSWORD'),
    database: optional('POSTGRES_DB', 'internet_calling'),
  },

  redis: {
    host: optional('REDIS_HOST', 'localhost'),
    port: parseInt(optional('REDIS_PORT', '6379'), 10),
    password: required('REDIS_PASSWORD'),
  },

  jwt: {
    privateKeyPath: optional('JWT_PRIVATE_KEY_PATH', './keys/jwt_private.pem'),
    publicKeyPath: optional('JWT_PUBLIC_KEY_PATH', './keys/jwt_public.pem'),
    accessExpiry: optional('JWT_ACCESS_EXPIRY', '15m'),
    refreshExpiry: optional('JWT_REFRESH_EXPIRY', '30d'),
  },

  serviceToken: required('SERVICE_TOKEN'),

  coturn: {
    secret: required('COTURN_SECRET'),
    realm: optional('COTURN_REALM', 'agentcall.example.com'),
  },

  fcm: {
    projectId: optional('FCM_PROJECT_ID', ''),
    serviceAccountKeyPath: optional('FCM_SERVICE_ACCOUNT_KEY_PATH', ''),
    serverKey: optional('FCM_SERVER_KEY', ''),
  },

  apns: {
    keyId: optional('APNS_KEY_ID', ''),
    teamId: optional('APNS_TEAM_ID', ''),
    privateKeyPath: optional('APNS_PRIVATE_KEY_PATH', ''),
    privateKey: optional('APNS_PRIVATE_KEY', ''),
  },

  oauth: {
    google: {
      clientId: optional('OAUTH_GOOGLE_CLIENT_ID', ''),
      clientSecret: optional('OAUTH_GOOGLE_CLIENT_SECRET', ''),
    },
    github: {
      clientId: optional('OAUTH_GITHUB_CLIENT_ID', ''),
      clientSecret: optional('OAUTH_GITHUB_CLIENT_SECRET', ''),
    },
    apple: {
      clientId: optional('OAUTH_APPLE_CLIENT_ID', ''),
      privateKey: optional('OAUTH_APPLE_PRIVATE_KEY', ''),
    },
  },

  backendApiUrl: optional('BACKEND_API_URL', 'http://localhost:4000/api/v1'),

  signaling: {
    maxMessageSize: parseInt(optional('SIGNALING_MAX_MESSAGE_SIZE', '262144'), 10),
    rateLimitMessages: parseInt(optional('SIGNALING_RATE_LIMIT_MESSAGES', '30'), 10),
    rateLimitWindowSec: parseInt(optional('SIGNALING_RATE_LIMIT_WINDOW', '10'), 10),
    connectionRateLimit: parseInt(optional('SIGNALING_CONNECTION_RATE_LIMIT', '5'), 10),
  },

  security: {
    corsAllowedOrigins: optional('CORS_ALLOWED_ORIGINS', '*'),
    bodyLimit: parseInt(optional('BODY_LIMIT_BYTES', '65536'), 10),
  },
} as const;
