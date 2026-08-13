import type { VoiceBridgeService } from '../voicebridge/service.js';
import type { VoiceCallSession } from '../voicebridge/types.js';
import type { CallReason } from '../common/types.js';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { getAgentIdentity } from './identity.js';
import { DEFAULT_AGENT_NAME } from '../voicebridge/ai-keys.js';
import { logger } from '../common/logger.js';
import { config } from '../common/config.js';

type ToolResult = CallToolResult;

function text(content: string): ToolResult {
  return { content: [{ type: 'text', text: content }] };
}

function error(msg: string): ToolResult {
  return { content: [{ type: 'text', text: msg }], isError: true };
}

/**
 * Ownership gate for per-call tools: the authenticated identity must be the
 * one that created the call (its agentId). Returns the loaded session so
 * callers avoid a second fetch. Not-found is kept distinct from forbidden so
 * a missing call still reports "Call not found" rather than revealing or
 * denying anything.
 */
async function authorizeCall(
  voicebridge: VoiceBridgeService,
  callId: string,
): Promise<{ ok: true; session: VoiceCallSession } | { ok: false; result: ToolResult }> {
  const session = await voicebridge.getCall(callId);
  if (!session) return { ok: false, result: error(`Error: Call not found: ${callId}`) };
  const identity = getAgentIdentity();
  if (session.agentId !== identity.agentName) {
    logger.warn(
      { callId, owner: session.agentId, requester: identity.agentName },
      '[MCP] denied per-call access by non-owner identity',
    );
    return {
      ok: false,
      result: error(`Error: Forbidden: call ${callId} belongs to a different AI identity`),
    };
  }
  return { ok: true, session };
}

const VALID_REASONS = ['clarification', 'approval', 'error', 'input_required'];

export interface McpTool {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  handler: (args: Record<string, unknown>) => Promise<ToolResult>;
}

export function createTools(voicebridge: VoiceBridgeService): McpTool[] {
  return [
    {
      name: 'create_call',
      description:
        'Initiate a voice call to get human input, clarification, or approval. The human will hear your message via their phone.',
      inputSchema: {
        type: 'object',
        required: ['context'],
        properties: {
          user_id: { type: 'string', description: 'User to call (defaults to solo user)', default: 'solo-user' },
          context: {
            type: 'object',
            required: ['reason', 'summary'],
            properties: {
              task_id: { type: 'string', description: 'Your task ID needing input' },
              reason: { type: 'string', enum: VALID_REASONS, description: 'Why you need human input' },
              summary: { type: 'string', description: 'What you need from the human', maxLength: 1000 },
              options: { type: 'array', items: { type: 'string' }, description: 'Options for the human to choose from' },
            },
          },
          priority: { type: 'string', enum: ['low', 'normal', 'high', 'urgent'], default: 'normal' },
        },
      },
      handler: async (args) => {
        const context = (args.context ?? {}) as Record<string, unknown>;
        const reason = String(context.reason ?? 'input_required');
        const summary = String(context.summary ?? '');
        if (!summary) return error('Error: summary is required in context');
        if (!VALID_REASONS.includes(reason)) {
          return error(`Error: reason must be one of: ${VALID_REASONS.join(', ')}`);
        }
        const identity = getAgentIdentity();
        try {
          const session = await voicebridge.createCall({
            userId: (args.user_id as string) ?? 'solo-user',
            agentId: identity.agentName || DEFAULT_AGENT_NAME,
            reason: reason as CallReason,
            summary,
            taskId: context.task_id as string | undefined,
            options: context.options as string[] | undefined,
            priority: (args.priority as 'low' | 'normal' | 'high' | 'urgent' | undefined) ?? 'normal',
          });
          return text(JSON.stringify({
            call_id: session.id,
            status: session.status,
            instruction: 'Use send_message to send text to the user. Use get_transcript to see their response. Use complete_call when done.',
          }, null, 2));
        } catch (err) {
          return error(`Error: ${err instanceof Error ? err.message : String(err)}`);
        }
      },
    },
    {
      name: 'send_message',
      description:
        'Send a text message to the human during an active call. The message will be spoken aloud on their phone using text-to-speech.',
      inputSchema: {
        type: 'object',
        required: ['call_id', 'content'],
        properties: {
          call_id: { type: 'string', description: 'Call ID from create_call' },
          content: { type: 'string', description: 'Text message to speak to the human', maxLength: 2000 },
        },
      },
      handler: async (args) => {
        const callId = args.call_id as string;
        const content = args.content as string;
        const auth = await authorizeCall(voicebridge, callId);
        if (!auth.ok) return auth.result;
        try {
          const msg = await voicebridge.addAiMessage(callId, content);
          if (!msg) return error(`Error: Call not found: ${callId}`);
          return text(JSON.stringify({
            message_id: msg.id,
            sent: true,
            spoken_to_human: true,
            instruction: 'Use get_transcript to check if the human has responded.',
          }, null, 2));
        } catch (err) {
          return error(`Error: ${err instanceof Error ? err.message : String(err)}`);
        }
      },
    },
    {
      name: 'get_transcript',
      description:
        'Get the conversation transcript from an active or completed call. Shows all messages between you and the human.',
      inputSchema: {
        type: 'object',
        required: ['call_id'],
        properties: {
          call_id: { type: 'string', description: 'Call ID from create_call' },
        },
      },
      handler: async (args) => {
        const callId = args.call_id as string;
        const auth = await authorizeCall(voicebridge, callId);
        if (!auth.ok) return auth.result;
        const messages = await voicebridge.getTranscript(callId);
        if (!messages) {
          return text(JSON.stringify({
            status: auth.session.status,
            message_count: auth.session.messages.length,
            instruction: 'Send a message with send_message, then check transcript again.',
          }, null, 2));
        }
        return text(JSON.stringify({ call_id: callId, messages }, null, 2));
      },
    },
    {
      name: 'complete_call',
      description:
        'Mark a call as complete and optionally store the result (what the human said, decisions made). After this, the call is ended.',
      inputSchema: {
        type: 'object',
        required: ['call_id'],
        properties: {
          call_id: { type: 'string', description: 'Call ID from create_call' },
          result: {
            type: 'object',
            properties: {
              transcript_summary: { type: 'string' },
              user_response: { type: 'string' },
              decision: { type: 'string' },
              selected_option: { type: 'string' },
              sentiment: { type: 'string', enum: ['positive', 'neutral', 'negative', 'urgent'] },
              action_items: { type: 'array', items: { type: 'string' } },
            },
          },
        },
      },
      handler: async (args) => {
        const callId = args.call_id as string;
        const result = args.result as Record<string, unknown> | undefined;
        const auth = await authorizeCall(voicebridge, callId);
        if (!auth.ok) return auth.result;
        try {
          const session = await voicebridge.completeCall(callId, result as never);
          if (!session) return error(`Error: Call not found: ${callId}`);
          return text(JSON.stringify({
            status: session.status,
            call_id: callId,
            instruction: 'Use get_transcript to review the full conversation.',
          }, null, 2));
        } catch (err) {
          return error(`Error: ${err instanceof Error ? err.message : String(err)}`);
        }
      },
    },
    {
      name: 'cancel_call',
      description: 'Cancel a pending or active call without completing it.',
      inputSchema: {
        type: 'object',
        required: ['call_id'],
        properties: {
          call_id: { type: 'string' },
          reason: { type: 'string', enum: ['resolved', 'timeout', 'error', 'user_requested'], default: 'resolved' },
        },
      },
      handler: async (args) => {
        const callId = args.call_id as string;
        const auth = await authorizeCall(voicebridge, callId);
        if (!auth.ok) return auth.result;
        try {
          const session = await voicebridge.cancelCall(callId);
          if (!session) return error(`Error: Call not found: ${callId}`);
          return text(JSON.stringify({ status: 'cancelled', call_id: callId }, null, 2));
        } catch (err) {
          return error(`Error: ${err instanceof Error ? err.message : String(err)}`);
        }
      },
    },
    {
      name: 'send_message_and_wait',
      description:
        'Send a text message to the human during an active call and wait for their reply. ' +
        (config.v2.engineV2
          ? 'Returns the reply when the turn ends (human speaks, call ends, or a noactivity escalation) — there is no server-side cap; timeout_seconds is an optional client window.'
          : 'Returns the reply if it arrives within the timeout window (max 45s). If not, returns a timeout so you can continue working and check back later with get_transcript.'),
      inputSchema: {
        type: 'object',
        required: ['call_id', 'content'],
        properties: {
          call_id: { type: 'string', description: 'Call ID from create_call' },
          content: { type: 'string', description: 'Text message to speak to the human', maxLength: 2000 },
          timeout_seconds: config.v2.engineV2
            ? {
                type: 'number',
                description: 'Optional client wait window in seconds. Omit to wait until the turn ends (reply, call end, or noactivity escalation). No server cap.',
                minimum: 1,
                maximum: 86400,
              }
            : {
                type: 'number',
                description: 'Max seconds to wait for a reply (1-45, default 15)',
                default: 15,
                minimum: 1,
                maximum: 45,
              },
        },
      },
      handler: async (args) => {
        const callId = args.call_id as string;
        const content = args.content as string;
        const auth = await authorizeCall(voicebridge, callId);
        if (!auth.ok) return auth.result;

        // ENGINE_V2: turn-lease semantics — timeout_seconds is an optional
        // client window (no maximum); absent = wait until the turn ends.
        // Flag off: today's capped behavior, unchanged.
        const rawTimeout = args.timeout_seconds as number | undefined;
        const clientWindowSeconds = config.v2.engineV2
          ? rawTimeout === undefined
            ? undefined
            : Math.max(rawTimeout, 1)
          : Math.min(Math.max(rawTimeout ?? 15, 1), 45);

        const disposeAiWait = voicebridge.registerAiWait(
          callId,
          clientWindowSeconds === undefined ? null : clientWindowSeconds * 1000,
        );

        // Subscribe BEFORE sending so a reply that lands the instant the message
        // is written is not missed (wake is a counter bump, not an edge).
        const watcher = voicebridge.createSessionWatcher(callId);
        try {
          const msg = await voicebridge.addAiMessage(callId, content);
          if (!msg) return error(`Error: Call not found: ${callId}`);

          const aiMessageTime = msg.createdAt;
          const deadline = clientWindowSeconds === undefined ? null : Date.now() + clientWindowSeconds * 1000;
          // Safety-net interval: the session watcher wakes the loop the moment
          // a user message or terminal transition is persisted, so replies are
          // delivered with no poll floor. This only fires if a change somehow
          // bypasses the in-process event bus (e.g. a future multi-instance run).
          const safetyNetMs = config.mcp.replyPollIntervalMs;
          // Lease-mode safety valve (roadmap R7): after this long with no user
          // activity the wait escalates to `noactivity` instead of blocking an
          // AI forever on a silent call. Advisory — the AI decides next.
          const escalationMs = config.v2.engineV2 ? config.v2.noactivityEscalationMs : Number.POSITIVE_INFINITY;
          const waitStartedAt = Date.now();

          while (deadline === null || Date.now() < deadline) {
            const session = await voicebridge.getCall(callId);
            if (!session) return error(`Error: Call not found: ${callId}`);

            if (session.status === 'completed' || session.status === 'cancelled') {
              const userMessages = session.messages.filter((m) => m.role === 'user');
              const lastUser = userMessages.at(-1);
              const note = lastUser ? lastUser.content : null;
              return text(JSON.stringify({
                outcome: 'call_ended',
                reason: session.status,
                message: `The call was ${session.status} while waiting for a reply.`,
                user_note: note,
                instruction: note
                  ? 'The user left a note when the call ended. Decide what to do next based on it (keep working, try again, or stop).'
                  : undefined,
              }, null, 2));
            }

            const replies = session.messages.filter(
              (m) => m.role === 'user' && m.createdAt > aiMessageTime,
            );
            const reply = replies.at(0);
            if (reply) {
              return text(JSON.stringify({
                outcome: 'reply',
                reply: { text: reply.content, received_at: reply.createdAt },
                exchange: { ai_message_id: msg.id, user_message_id: reply.id },
              }, null, 2));
            }

            // Noactivity escalation (lease mode): surface the silence and let
            // the AI decide (continue / prompt / end) — never block forever.
            if (config.v2.engineV2 && Date.now() - waitStartedAt >= escalationMs) {
              return text(JSON.stringify({
                outcome: 'noactivity',
                silent_seconds: Math.round(escalationMs / 1000),
                message: 'No human activity since the message was sent. The call is still active.',
                instruction: 'The call remains open. Call send_message_and_wait again to resume waiting, send another message, or end the call.',
              }, null, 2));
            }

            const remainingMs = deadline === null ? safetyNetMs : deadline - Date.now();
            if (remainingMs <= 0) break;
            await watcher.waitForChange(Math.min(safetyNetMs, remainingMs));
          }

          return text(JSON.stringify({
            outcome: 'timeout',
            waited_seconds: clientWindowSeconds ?? null,
            message: clientWindowSeconds === undefined
              ? 'The wait was interrupted without a reply or terminal event.'
              : 'No reply received within the client window. The call is still active — use get_transcript to check for replies later, or call send_message_and_wait again.',
            instruction: 'You can continue working and check back with get_transcript, or send another message with send_message_and_wait.',
          }, null, 2));
        } finally {
          watcher.dispose();
          disposeAiWait();
        }
      },
    },
  ];
}
