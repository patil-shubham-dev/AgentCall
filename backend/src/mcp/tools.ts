import type { VoiceBridgeService } from '../voicebridge/service.js';
import type { CallReason } from '../common/types.js';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { getAgentIdentity } from './identity.js';
import { DEFAULT_AGENT_NAME } from '../voicebridge/ai-keys.js';

type ToolResult = CallToolResult;

function text(content: string): ToolResult {
  return { content: [{ type: 'text', text: content }] };
}

function error(msg: string): ToolResult {
  return { content: [{ type: 'text', text: msg }], isError: true };
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
        const messages = await voicebridge.getTranscript(callId);
        if (!messages) {
          const session = await voicebridge.getCall(callId);
          if (!session) return error(`Call not found: ${callId}`);
          return text(JSON.stringify({
            status: session.status,
            message_count: session.messages.length,
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
        'Send a text message to the human during an active call and wait for their reply (up to timeout_seconds). ' +
        "Returns the human's spoken or typed response if they reply within the window. " +
        'If no reply arrives in time, returns a timeout so you can continue working and check back later with get_transcript.',
      inputSchema: {
        type: 'object',
        required: ['call_id', 'content'],
        properties: {
          call_id: { type: 'string', description: 'Call ID from create_call' },
          content: { type: 'string', description: 'Text message to speak to the human', maxLength: 2000 },
          timeout_seconds: {
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
        const timeoutSeconds = Math.min(Math.max((args.timeout_seconds as number) ?? 15, 1), 45);

        const msg = await voicebridge.addAiMessage(callId, content);
        if (!msg) return error(`Error: Call not found: ${callId}`);

        const aiMessageTime = msg.createdAt;
        const deadline = Date.now() + timeoutSeconds * 1000;
        const pollIntervalMs = 2000;

        while (Date.now() < deadline) {
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

          await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
        }

        return text(JSON.stringify({
          outcome: 'timeout',
          waited_seconds: timeoutSeconds,
          message: 'No reply received within the timeout window. The call is still active — use get_transcript to check for replies later, or call send_message_and_wait again.',
          instruction: 'You can continue working and check back with get_transcript, or send another message with send_message_and_wait.',
        }, null, 2));
      },
    },
  ];
}
