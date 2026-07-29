import * as client from './client.js';

function text(content: string) {
  return { content: [{ type: 'text' as const, text: content }] };
}

function error(msg: string) {
  return { content: [{ type: 'text' as const, text: msg }], isError: true as const };
}

export const createCallTool = {
  name: 'create_call',
  description: 'Initiate a voice call to get human input, clarification, or approval. The human will hear your message via their phone.',
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
          reason: {
            type: 'string',
            enum: ['clarification', 'approval', 'error', 'input_required'],
            description: 'Why you need human input',
          },
          summary: { type: 'string', description: 'What you need from the human', maxLength: 1000 },
          options: { type: 'array', items: { type: 'string' }, description: 'Options for the human to choose from' },
        },
      },
      priority: { type: 'string', enum: ['low', 'normal', 'high', 'urgent'], default: 'normal' },
    },
  },
  handler: async (args: Record<string, unknown>) => {
    const context = args.context as Record<string, string | string[]>;
    const result = await client.createCall({
      user_id: (args.user_id as string) ?? 'solo-user',
      agent_id: 'ai-agent',
      context: {
        task_id: context.task_id as string | undefined,
        reason: context.reason as string,
        summary: context.summary as string,
        options: context.options as string[] | undefined,
      },
      priority: args.priority as string | undefined,
    });

    if ('error' in result) return error(`Error: ${result.message ?? result.error}`);

    return text(JSON.stringify({
      call_id: result.data.call_id,
      status: result.data.status,
      instruction: 'Use send_message to send text to the user. Use get_transcript to see their response. Use complete_call when done.',
    }, null, 2));
  },
};

export const sendMessageTool = {
  name: 'send_message',
  description: 'Send a text message to the human during an active call. The message will be spoken aloud on their phone using text-to-speech.',
  inputSchema: {
    type: 'object',
    required: ['call_id', 'content'],
    properties: {
      call_id: { type: 'string', description: 'Call ID from create_call' },
      content: { type: 'string', description: 'Text message to speak to the human', maxLength: 2000 },
    },
  },
  handler: async (args: Record<string, unknown>) => {
    const callId = args.call_id as string;
    const content = args.content as string;

    const result = await client.sendMessage(callId, content);
    if ('error' in result) return error(`Error: ${result.message ?? result.error}`);

    return text(JSON.stringify({
      message_id: result.data.message_id,
      sent: true,
      spoken_to_human: true,
      instruction: 'Use get_transcript to check if the human has responded.',
    }, null, 2));
  },
};

export const getTranscriptTool = {
  name: 'get_transcript',
  description: 'Get the conversation transcript from an active or completed call. Shows all messages between you and the human.',
  inputSchema: {
    type: 'object',
    required: ['call_id'],
    properties: {
      call_id: { type: 'string', description: 'Call ID from create_call' },
    },
  },
  handler: async (args: Record<string, unknown>) => {
    const callId = args.call_id as string;
    const result = await client.getTranscript(callId);

    if ('error' in result) {
      const getResult = await client.getCall(callId);
      if ('error' in getResult) return error(`Call not found: ${callId}`);
      return text(JSON.stringify({
        status: getResult.data.status,
        message_count: getResult.data.message_count,
        instruction: 'Send a message with send_message, then check transcript again.',
      }, null, 2));
    }

    return text(JSON.stringify({
      call_id: callId,
      messages: result.data.messages,
    }, null, 2));
  },
};

export const completeCallTool = {
  name: 'complete_call',
  description: 'Mark a call as complete and optionally store the result (what the human said, decisions made). After this, the call is ended.',
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
  handler: async (args: Record<string, unknown>) => {
    const callId = args.call_id as string;
    const result = args.result as Record<string, unknown> | undefined;

    const r = await client.completeCall(callId, result);
    if ('error' in r) return error(`Error: ${r.message ?? r.error}`);

    return text(JSON.stringify({
      status: 'completed',
      call_id: callId,
      instruction: 'Use get_transcript to review the full conversation.',
    }, null, 2));
  },
};

export const cancelCallTool = {
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
  handler: async (args: Record<string, unknown>) => {
    const callId = args.call_id as string;
    const reason = (args.reason as string) ?? 'resolved';

    const r = await client.cancelCall(callId, reason);
    if ('error' in r) return error(`Error: ${r.message ?? r.error}`);

    return text(JSON.stringify({ status: 'cancelled', call_id: callId }, null, 2));
  },
};

export const sendMessageAndWaitTool = {
  name: 'send_message_and_wait',
  description: 'Send a text message to the human during an active call and wait for their reply (up to timeout_seconds). ' +
    'Returns the human\'s spoken or typed response if they reply within the window. ' +
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
  handler: async (args: Record<string, unknown>) => {
    const callId = args.call_id as string;
    const content = args.content as string;
    const timeoutSeconds = Math.min(Math.max((args.timeout_seconds as number) ?? 15, 1), 45);

    // Send the message first
    const sendResult = await client.sendMessage(callId, content);
    if ('error' in sendResult) return error(`Error sending message: ${sendResult.message ?? sendResult.error}`);

    const aiMessageId = sendResult.data.message_id;
    const deadline = Date.now() + timeoutSeconds * 1000;
    const pollIntervalMs = 2000;

    while (Date.now() < deadline) {
      const replyResult = await client.getPendingReply(callId, aiMessageId);
      if ('error' in replyResult) {
        return error(`Error checking for reply: ${replyResult.message ?? replyResult.error}`);
      }

      const { reply, call_status } = replyResult.data;

      if (call_status === 'completed' || call_status === 'cancelled') {
        return text(JSON.stringify({
          outcome: 'call_ended',
          reason: call_status,
          message: `The call was ${call_status} while waiting for a reply.`,
        }, null, 2));
      }

      if (reply) {
        return text(JSON.stringify({
          outcome: 'reply',
          reply: {
            text: reply.content,
            received_at: reply.created_at,
          },
          exchange: {
            ai_message_id: aiMessageId,
            user_message_id: reply.id,
          },
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
};

export const tools = [
  createCallTool,
  sendMessageTool,
  getTranscriptTool,
  completeCallTool,
  cancelCallTool,
  sendMessageAndWaitTool,
];
