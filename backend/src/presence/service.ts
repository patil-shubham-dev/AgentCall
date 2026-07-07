import { redis } from '../db/redis.js';
import { db } from '../db/connection.js';
import type { PresenceStatus, Platform } from '../common/types.js';

const PRESENCE_TTL = 60;
const HEARTBEAT_TIMEOUT = 30;

interface PresenceData {
  status: PresenceStatus;
  device_id: string;
  platform: Platform;
  last_seen: string;
}

function presenceKey(userId: string): string {
  return `presence:${userId}`;
}

export async function setPresence(
  userId: string,
  deviceId: string,
  platform: Platform,
  status: PresenceStatus = 'online',
): Promise<void> {
  const data: PresenceData = {
    status,
    device_id: deviceId,
    platform,
    last_seen: new Date().toISOString(),
  };

  await redis.set(presenceKey(userId), JSON.stringify(data), 'EX', PRESENCE_TTL);
}

export async function getPresence(userId: string): Promise<PresenceData | null> {
  const raw = await redis.get(presenceKey(userId));
  if (!raw) return null;
  return JSON.parse(raw) as PresenceData;
}

export async function getUserPresence(userId: string): Promise<{
  status: PresenceStatus;
  last_seen: string | null;
  dnd: boolean;
}> {
  const presence = await getPresence(userId);
  const user = await db('users').where({ id: userId }).select('do_not_disturb', 'updated_at').first();

  if (!presence) {
    return {
      status: 'offline',
      last_seen: user ? (user as { updated_at: string }).updated_at : null,
      dnd: user ? (user as { do_not_disturb: boolean }).do_not_disturb : false,
    };
  }

  const timeSinceLastSeen = (Date.now() - new Date(presence.last_seen).getTime()) / 1000;

  let status = presence.status;
  if (timeSinceLastSeen > HEARTBEAT_TIMEOUT && status === 'online') {
    status = 'away';
  }

  return {
    status,
    last_seen: presence.last_seen,
    dnd: user ? (user as { do_not_disturb: boolean }).do_not_disturb : false,
  };
}

export async function refreshHeartbeat(userId: string, deviceId: string, platform: Platform): Promise<void> {
  await setPresence(userId, deviceId, platform, 'online');
}

export async function setBusy(userId: string, deviceId: string, platform: Platform): Promise<void> {
  await setPresence(userId, deviceId, platform, 'busy');
}

export async function setAway(userId: string, deviceId: string, platform: Platform): Promise<void> {
  await setPresence(userId, deviceId, platform, 'away');
}

export async function clearPresence(userId: string): Promise<void> {
  await redis.del(presenceKey(userId));
}

export async function getActiveDevices(userId: string): Promise<Array<{ platform: Platform; push_enabled: boolean }>> {
  const devices = await db('devices')
    .where({ user_id: userId, is_active: true })
    .select('platform', 'push_token');

  return devices.map((d) => ({
    platform: d.platform as Platform,
    push_enabled: (d.push_token as string | null) !== null && d.push_token !== '',
  }));
}
