import { vi } from 'vitest';
import crypto from 'node:crypto';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Generate ephemeral RS256 key pair for JWT tests
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
  modulusLength: 2048,
  publicKeyEncoding: { type: 'spki', format: 'pem' },
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
});

// Write keys to temp files for the test duration
const keysDir = path.join(__dirname, '..', '..', 'keys');
if (!fs.existsSync(keysDir)) {
  fs.mkdirSync(keysDir, { recursive: true });
}
fs.writeFileSync(path.join(keysDir, 'jwt_private.pem'), privateKey);
fs.writeFileSync(path.join(keysDir, 'jwt_public.pem'), publicKey);

// Mock ioredis
vi.mock('ioredis', () => {
  const IORedisMock = vi.fn(() => ({
    get: vi.fn(),
    set: vi.fn(),
    setex: vi.fn(),
    del: vi.fn(),
    exists: vi.fn(),
    expire: vi.fn(),
    ping: vi.fn().mockResolvedValue('PONG'),
    on: vi.fn(),
    connect: vi.fn().mockResolvedValue(undefined),
    disconnect: vi.fn(),
    quit: vi.fn(),
    status: 'ready',
  }));
  return { default: IORedisMock };
});

// Mock knex
vi.mock('../db/connection.js', async () => {
  const queryBuilder = {
    insert: vi.fn().mockReturnThis(),
    select: vi.fn().mockReturnThis(),
    where: vi.fn().mockReturnThis(),
    whereIn: vi.fn().mockReturnThis(),
    whereNotNull: vi.fn().mockReturnThis(),
    whereNull: vi.fn().mockReturnThis(),
    orWhere: vi.fn().mockReturnThis(),
    first: vi.fn(),
    orderBy: vi.fn().mockReturnThis(),
    limit: vi.fn().mockReturnThis(),
    update: vi.fn().mockReturnThis(),
    delete: vi.fn().mockReturnThis(),
    returning: vi.fn().mockReturnThis(),
    from: vi.fn().mockReturnThis(),
    join: vi.fn().mockReturnThis(),
    onConflict: vi.fn().mockReturnThis(),
    merge: vi.fn().mockReturnThis(),
    raw: vi.fn().mockResolvedValue([{ '?column?': 1 }]),
    destroy: vi.fn().mockResolvedValue(undefined),
  };

  const knexMock = vi.fn(() => queryBuilder);
  Object.assign(knexMock, queryBuilder);

  return {
    db: knexMock,
    checkConnection: vi.fn().mockResolvedValue(undefined),
    destroyConnection: vi.fn().mockResolvedValue(undefined),
  };
});
