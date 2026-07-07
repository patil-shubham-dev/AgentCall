import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../db/redis.js', () => ({
  redis: { get: vi.fn(), set: vi.fn(), del: vi.fn() },
}));

vi.mock('../db/connection.js', () => {
  const builder = {
    where: vi.fn().mockReturnThis(),
    whereNotNull: vi.fn().mockReturnThis(),
    whereNull: vi.fn().mockReturnThis(),
    first: vi.fn(),
    insert: vi.fn().mockReturnThis(),
    update: vi.fn().mockReturnThis(),
    returning: vi.fn(),
    select: vi.fn().mockReturnThis(),
    orderBy: vi.fn().mockReturnThis(),
    limit: vi.fn(),
    onConflict: vi.fn().mockReturnThis(),
    merge: vi.fn(),
  };
  const db = vi.fn().mockReturnValue(builder);
  db.raw = vi.fn();
  return { db };
});

const testKeyPair = vi.hoisted(() => {
  const crypto = require('node:crypto');
  return crypto.generateKeyPairSync('rsa', {
    modulusLength: 2048,
    publicKeyEncoding: { type: 'spki', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  });
});

vi.mock('node:fs', () => ({
  readFileSync: vi.fn((path: string) => {
    if (path.includes('private')) return testKeyPair.privateKey;
    return testKeyPair.publicKey;
  }),
}));

import * as authService from '../auth/service.js';
import { UnauthorizedError } from '../common/errors.js';

describe('AuthService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('generateTURNCredentials', () => {
    it('should generate valid TURN credentials', () => {
      const result = authService.generateTURNCredentials('user-123');
      expect(result).toHaveProperty('username');
      expect(result).toHaveProperty('credential');
      expect(result).toHaveProperty('ttl');
      expect(result.ttl).toBe(3600);
      expect(result.username).toContain('user-123');
    });

    it('should generate alphanumeric credential', () => {
      const result = authService.generateTURNCredentials('user-123');
      expect(result.credential).toBeTruthy();
      expect(typeof result.credential).toBe('string');
    });
  });

  describe('validateServiceToken', () => {
    it('should return true for valid service token', async () => {
      const { config } = await import('../common/config.js');
      const result = await authService.validateServiceToken(config.serviceToken);
      expect(result).toBe(true);
    });

    it('should return false for invalid service token', async () => {
      const result = await authService.validateServiceToken('wrong-token');
      expect(result).toBe(false);
    });
  });

  describe('signAccessToken / verifyAccessToken', () => {
    it('should sign and verify a token successfully', () => {
      const token = authService.signAccessToken('user-123', 'device-456', 'user');
      expect(token).toBeTruthy();

      const payload = authService.verifyAccessToken(token);
      expect(payload.sub).toBe('user-123');
      expect(payload.did).toBe('device-456');
      expect(payload.role).toBe('user');
    });

    it('should sign token without device ID', () => {
      const token = authService.signAccessToken('user-123');
      const payload = authService.verifyAccessToken(token);
      expect(payload.sub).toBe('user-123');
      expect(payload.did).toBeUndefined();
    });

    it('should throw for invalid token', () => {
      expect(() => authService.verifyAccessToken('invalid')).toThrow(UnauthorizedError);
    });
  });

  describe('registerDevice', () => {
    it('should register a device and return device ID', async () => {
      const { db } = await import('../db/connection.js');
      (db().returning as ReturnType<typeof vi.fn>).mockResolvedValue([{ id: 'device-1' }]);

      const deviceId = await authService.registerDevice('user-1', 'android', 'fcm-token', 'Pixel 9');
      expect(deviceId).toBe('device-1');
    });

    it('should register without push token', async () => {
      const { db } = await import('../db/connection.js');
      (db().returning as ReturnType<typeof vi.fn>).mockResolvedValue([{ id: 'device-2' }]);

      const deviceId = await authService.registerDevice('user-1', 'web', undefined, 'Chrome');
      expect(deviceId).toBe('device-2');
    });
  });

  describe('removeDevice', () => {
    it('should deactivate a device', async () => {
      await authService.removeDevice('device-1');
      const { db } = await import('../db/connection.js');
      expect(db().where).toHaveBeenCalledWith({ id: 'device-1' });
    });
  });
});
