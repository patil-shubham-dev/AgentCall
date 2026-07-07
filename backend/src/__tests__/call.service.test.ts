import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../db/redis.js', () => ({
  redis: { get: vi.fn(), set: vi.fn(), del: vi.fn() },
}));

vi.mock('../db/connection.js', () => {
  const builder = {
    where: vi.fn().mockReturnThis(),
    whereIn: vi.fn().mockReturnThis(),
    orWhere: vi.fn().mockReturnThis(),
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

import * as callService from '../call/service.js';
import { NotFoundError } from '../common/errors.js';

describe('CallService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('createCall', () => {
    it('should create a call and return callId', async () => {
      const { db } = await import('../db/connection.js');
      (db().returning as ReturnType<typeof vi.fn>).mockResolvedValue([{ id: 'call-1' }]);

      const result = await callService.createCall(
        'user-1', 'agent-1',
        { reason: 'clarification', summary: 'Need input' },
        'high', 30,
      );

      expect(result.callId).toBe('call-1');
      expect(result.status).toBe('requested');
    });
  });

  describe('getCall', () => {
    it('should return call when found', async () => {
      const { db } = await import('../db/connection.js');
      (db().first as ReturnType<typeof vi.fn>).mockResolvedValue({
        id: 'call-1', user_id: 'user-1', agent_id: 'agent-1',
        status: 'requested', priority: 'normal', reason: 'clarification',
        context: { summary: 'test' }, timeout_seconds: 30,
      });

      const call = await callService.getCall('call-1');
      expect(call.id).toBe('call-1');
      expect(call.status).toBe('requested');
    });

    it('should throw NotFoundError when not found', async () => {
      const { db } = await import('../db/connection.js');
      (db().first as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
      await expect(callService.getCall('nonexistent')).rejects.toThrow(NotFoundError);
    });
  });

  describe('updateCallStatus', () => {
    it('should update to ended and remove active call', async () => {
      const { db } = await import('../db/connection.js');
      const { redis } = await import('../db/redis.js');
      (db().first as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'call-1', user_id: 'user-1' });

      await callService.updateCallStatus('call-1', 'ended');
      expect(redis.del).toHaveBeenCalledWith('active_call:user-1');
    });
  });

  describe('completeCall', () => {
    it('should mark call as completed', async () => {
      const { db } = await import('../db/connection.js');
      const { redis } = await import('../db/redis.js');
      (db().first as ReturnType<typeof vi.fn>).mockResolvedValue({
        id: 'call-1', user_id: 'user-1', connected_at: new Date().toISOString(),
      });

      await callService.completeCall('call-1', { user_response: 'Yes', sentiment: 'positive' });

      expect(db().update).toHaveBeenCalledWith(expect.objectContaining({ status: 'ended' }));
      expect(redis.del).toHaveBeenCalledWith('active_call:user-1');
    });
  });

  describe('isUserBusy', () => {
    it('should return true if active call exists', async () => {
      const { redis } = await import('../db/redis.js');
      (redis.get as ReturnType<typeof vi.fn>).mockResolvedValue('call-1');
      expect(await callService.isUserBusy('user-1')).toBe(true);
    });

    it('should return true if presence is busy', async () => {
      const { redis } = await import('../db/redis.js');
      (redis.get as ReturnType<typeof vi.fn>)
        .mockResolvedValueOnce(null)
        .mockResolvedValueOnce(JSON.stringify({ status: 'busy', device_id: 'd1', platform: 'android', last_seen: new Date().toISOString() }));
      expect(await callService.isUserBusy('user-1')).toBe(true);
    });

    it('should return false if available', async () => {
      const { redis } = await import('../db/redis.js');
      (redis.get as ReturnType<typeof vi.fn>).mockResolvedValue(null);
      (redis.get as ReturnType<typeof vi.fn>).mockResolvedValue(null);
      expect(await callService.isUserBusy('user-1')).toBe(false);
    });
  });

  describe('addParticipant', () => {
    it('should add a participant', async () => {
      await callService.addParticipant('call-1', 'user-1', 'caller');
    });
  });
});
