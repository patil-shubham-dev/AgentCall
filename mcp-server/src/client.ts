import { config } from './config.js';
import { logger } from './logger.js';

type ApiResponse<T> =
  | { data: T }
  | { error: string; message?: string };

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

export function createCall(input: {
  user_id: string;
  agent_id: string;
  context: { task_id?: string; reason: string; summary: string; options?: string[] };
  priority?: string;
}) {
  return apiRequest<{ call_id: string; status: string; created_at: string }>('POST', '/calls', {
    user_id: input.user_id,
    agent_id: input.agent_id,
    context: input.context,
    priority: input.priority,
    summary: input.context.summary,
    reason: input.context.reason,
  });
}

export function getCall(callId: string) {
  return apiRequest<{
    call_id: string;
    status: string;
    user_id: string;
    agent_id: string;
    result?: Record<string, unknown>;
    message_count: number;
  }>('GET', `/calls/${callId}`);
}

export function sendMessage(callId: string, content: string) {
  return apiRequest<{ message_id: string; role: string; content: string; created_at: string }>(
    'POST', `/calls/${callId}/messages`, { content },
  );
}

export function getTranscript(callId: string) {
  return apiRequest<{ call_id: string; messages: Array<{ role: string; content: string; type: string; created_at: string }> }>(
    'GET', `/calls/${callId}/transcript`,
  );
}

export function completeCall(callId: string, result?: Record<string, unknown>) {
  return apiRequest<{ status: string; call_id: string }>('POST', `/calls/${callId}/complete`, { result });
}

export function cancelCall(callId: string, reason = 'resolved') {
  return apiRequest<{ status: string }>('POST', `/calls/${callId}/cancel`, { reason });
}
