import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../db/redis.js', () => ({
  redis: { get: vi.fn(), set: vi.fn(), del: vi.fn() },
}));

vi.mock('../db/connection.js', () => {
  const builder = {
    where: vi.fn().mockReturnThis(),
    first: vi.fn(),
    select: vi.fn().mockReturnThis(),
    insert: vi.fn(),
    update: vi.fn(),
    returning: vi.fn(),
    orderBy: vi.fn().mockReturnThis(),
    limit: vi.fn(),
  };
  const db = vi.fn().mockReturnValue(builder);
  db.raw = vi.fn();
  return { db };
});

import * as presenceService from '../presence/service.js';

describe('PresenceService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('setPresence / getPresence', () => {
    it('should set and retrieve presence data', async () => {
      const { redis } = await import('../db/redis.js');
      (redis.get as ReturnType<typeof vi.fn>).mockResolvedValue(
        JSON.stringify({ status: 'online', device_id: 'device-1', platform: 'android', last_seen: new Date().toISOString() }),
      );

      await presenceService.setPresence('user-1', 'device-1', 'android', 'online');
      expect(redis.set).toHaveBeenCalledWith('presence:user-1', expect.any(String), 'EX', 60);

      const presence = await presenceService.getPresence('user-1');
      expect(presence).not.toBeNull();
      expect(presence!.status).toBe('online');
    });

    it('should return null for unknown user', async () => {
      const { redis } = await import('../db/redis.js');
      (redis.get as ReturnType<typeof vi.fn>).mockResolvedValue(null);

      const presence = await presenceService.getPresence('unknown');
      expect(presence).toBeNull();
    });
  });

  describe('getUserPresence', () => {
    it('should return offline when no presence data', async () => {
      const { redis } = await import('../db/redis.js');
      const { db } = await import('../db/connection.js');
      (redis.get as ReturnType<typeof vi.fn>).mockResolvedValue(null);
      (db().first as ReturnType<typeof vi.fn>).mockResolvedValue({ do_not_disturb: false, updated_at: new Date().toISOString() });

      const result = await presenceService.getUserPresence('user-1');
      expect(result.status).toBe('offline');
    });

    it('should return online from presence data', async () => {
      const { redis } = await import('../db/redis.js');
      (redis.get as ReturnType<typeof vi.fn>).mockResolvedValue(
        JSON.stringify({ status: 'online', device_id: 'd1', platform: 'ios', last_seen: new Date().toISOString() }),
      );

      const result = await presenceService.getUserPresence('user-1');
      expect(result.status).toBe('online');
    });

    it('should return away if last seen exceeds heartbeat timeout', async () => {
      const { redis } = await import('../db/redis.js');
      const oldDate = new Date(Date.now() - 60_000).toISOString();
      (redis.get as ReturnType<typeof vi.fn>).mockResolvedValue(
        JSON.stringify({ status: 'online', device_id: 'd1', platform: 'android', last_seen: oldDate }),
      );

      const result = await presenceService.getUserPresence('user-1');
      expect(result.status).toBe('away');
    });
  });

  describe('refreshHeartbeat', () => {
    it('should update presence TTL', async () => {
      const { redis } = await import('../db/redis.js');
      await presenceService.refreshHeartbeat('user-1', 'device-1', 'android');
      expect(redis.set).toHaveBeenCalledWith('presence:user-1', expect.any(String), 'EX', 60);
    });
  });

  describe('clearPresence', () => {
    it('should delete presence key', async () => {
      const { redis } = await import('../db/redis.js');
      await presenceService.clearPresence('user-1');
      expect(redis.del).toHaveBeenCalledWith('presence:user-1');
    });
  });
});
