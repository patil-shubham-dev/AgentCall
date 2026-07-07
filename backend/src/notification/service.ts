import { db } from '../db/connection.js';
import { redis } from '../db/redis.js';
import { config } from '../common/config.js';
import { logger } from '../common/logger.js';
import type { Platform } from '../common/types.js';

interface PushPayload {
  type: 'call_incoming' | 'task_complete' | 'call_missed';
  call_id?: string;
  task_id?: string;
  caller_name?: string;
  context_summary?: string;
  priority?: string;
  summary?: string;
}

async function sendFCM(token: string, payload: PushPayload, ttl = 30): Promise<boolean> {
  if (!config.fcm.serverKey) {
    logger.warn('FCM not configured, skipping push');
    return false;
  }

  try {
    const response = await fetch('https://fcm.googleapis.com/fcm/send', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `key=${config.fcm.serverKey}`,
      },
      body: JSON.stringify({
        to: token,
        priority: 'high',
        time_to_live: ttl,
        data: payload,
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      logger.error({ status: response.status, body }, 'FCM send failed');
      return false;
    }

    return true;
  } catch (err) {
    logger.error({ err }, 'FCM send error');
    return false;
  }
}

async function sendAPNs(token: string, payload: PushPayload): Promise<boolean> {
  if (!config.apns.keyId || !config.apns.teamId || !config.apns.privateKey) {
    logger.warn('APNs not configured, skipping push');
    return false;
  }

  try {
    const response = await fetch(`https://api.push.apple.com/3/device/${token}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        Authorization: `bearer ${config.apns.privateKey}`,
        'apns-topic': 'com.agentcall.app',
        'apns-push-type': 'alert',
        'apns-priority': '10',
      },
      body: JSON.stringify({
        aps: {
          alert: { title: payload.caller_name ?? 'AI Call', body: payload.context_summary ?? '' },
          badge: 1,
          sound: 'call.caf',
          'mutable-content': 1,
        },
        ...payload,
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      logger.error({ status: response.status, body }, 'APNs send failed');
      return false;
    }

    return true;
  } catch (err) {
    logger.error({ err }, 'APNs send error');
    return false;
  }
}

export async function sendPush(
  userId: string,
  deviceId: string,
  platform: Platform,
  pushToken: string,
  payload: PushPayload,
): Promise<boolean> {
  let delivered = false;

  if (platform === 'android') {
    delivered = await sendFCM(pushToken, payload);
  } else if (platform === 'ios') {
    delivered = await sendAPNs(pushToken, payload);
  }

  await db('notification_log').insert({
    user_id: userId,
    device_id: deviceId,
    call_id: payload.call_id ?? null,
    notification_type: payload.type,
    status: delivered ? 'delivered' : 'failed',
    provider: platform === 'android' ? 'fcm' : 'apns',
    delivered_at: delivered ? new Date() : null,
  });

  return delivered;
}

export async function sendPushToUser(
  userId: string,
  payload: PushPayload,
): Promise<{ delivered: number; total: number }> {
  const devices = await db('devices')
    .where({ user_id: userId, is_active: true })
    .whereNotNull('push_token')
    .select('id', 'platform', 'push_token');

  if (devices.length === 0) {
    logger.warn({ userId }, 'No push-capable devices found');
    return { delivered: 0, total: 0 };
  }

  let delivered = 0;
  const results = await Promise.allSettled(
    devices.map((d) =>
      sendPush(
        userId,
        d.id,
        d.platform as Platform,
        d.push_token as string,
        payload,
      ),
    ),
  );

  for (const r of results) {
    if (r.status === 'fulfilled' && r.value) delivered++;
  }

  logger.info({ userId, delivered, total: devices.length }, 'Push notification sent');
  return { delivered, total: devices.length };
}

export async function processQueuedNotifications(): Promise<void> {
  const queued = await db('notification_log')
    .where({ status: 'queued' })
    .where('created_at', '>', new Date(Date.now() - 300_000))
    .limit(100);

  for (const n of queued) {
    const userDevice = await db('devices')
      .where({ id: n.device_id, is_active: true })
      .first();

    if (!userDevice || !userDevice.push_token) {
      await db('notification_log').where({ id: n.id }).update({ status: 'failed', error_message: 'No device' });
      continue;
    }

    const payload = JSON.parse(JSON.stringify(n.metadata ?? {})) as PushPayload;
    const delivered = await sendPush(
      n.user_id,
      n.device_id,
      userDevice.platform as Platform,
      userDevice.push_token as string,
      payload,
    );

    await db('notification_log')
      .where({ id: n.id })
      .update({
        status: delivered ? 'delivered' : 'failed',
        delivered_at: delivered ? new Date() : null,
      });
  }
}
