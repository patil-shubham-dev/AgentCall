import { config } from './config.js';
import { logger } from './logger.js';

interface CreateCallInput {
  user_id: string;
  agent_id: string;
  context: {
    task_id?: string;
    reason: string;
    summary: string;
    options?: string[];
  };
  priority?: string;
  timeout_seconds?: number;
}

interface ApiResponse<T> {
  data?: T;
  error?: string;
  message?: string;
}

async function apiRequest<T>(
  method: string,
  path: string,
  body?: Record<string, unknown>,
): Promise<ApiResponse<T>> {
  const url = `${config.backendApiUrl}${path}`;

  try {
    const response = await fetch(url, {
      method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${config.serviceToken}`,
      },
      body: body ? JSON.stringify(body) : undefined,
    });

    const data = await response.json() as T;

    if (!response.ok) {
      const err = data as unknown as { error?: string; message?: string };
      return { error: err.error ?? 'API_ERROR', message: err.message ?? `HTTP ${response.status}` };
    }

    return { data };
  } catch (err) {
    logger.error({ err, url }, 'API request failed');
    return { error: 'NETWORK_ERROR', message: 'Failed to reach backend' };
  }
}

export async function createCall(input: CreateCallInput) {
  return apiRequest<{ call_id: string; status: string; expires_at?: string }>(
    'POST',
    '/calls',
    input as unknown as Record<string, unknown>,
  );
}

export async function getCall(callId: string) {
  return apiRequest<{
    call_id: string;
    status: string;
    user_id: string;
    agent_id: string;
    result?: Record<string, unknown>;
    duration_seconds?: number;
  }>('GET', `/calls/${callId}`);
}

export async function cancelCall(callId: string, reason = 'resolved') {
  return apiRequest<{ status: string }>('POST', `/calls/${callId}/cancel`, { reason });
}

export async function completeCall(callId: string, result: Record<string, unknown>) {
  return apiRequest<{ status: string }>('POST', `/calls/${callId}/complete`, { result });
}

export async function queryPresence(userId: string) {
  return apiRequest<{
    user_id: string;
    status: string;
    last_seen?: string;
    dnd: boolean;
    devices: Array<{ platform: string; push_enabled: boolean }>;
  }>('GET', `/users/${userId}/presence`);
}

export async function sendNotification(
  userId: string,
  type: string,
  payload: Record<string, unknown>,
) {
  return apiRequest<{ status: string; device_targets: number }>(
    'POST',
    '/notifications',
    { user_id: userId, type, payload },
  );
}
