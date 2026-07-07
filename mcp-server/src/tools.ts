import { z } from 'zod';
import * as client from './client.js';
import { logger } from './logger.js';
import { config } from './config.js';

export const createCallTool = {
  name: 'create_call',
  description: 'Initiate a voice call to a human user for clarification, approval, or input',
  inputSchema: {
    type: 'object',
    required: ['user_id', 'context'],
    properties: {
      user_id: { type: 'string', description: 'Unique identifier of the user to call' },
      context: {
        type: 'object',
        required: ['reason', 'summary'],
        properties: {
          task_id: { type: 'string', description: 'Reference to the AI task needing input' },
          reason: {
            type: 'string',
            enum: ['clarification', 'approval', 'error', 'input_required'],
            description: 'Category of why human input is needed',
          },
          summary: { type: 'string', description: 'Brief explanation of what the agent needs', maxLength: 1000 },
          options: { type: 'array', items: { type: 'string' }, description: 'Pre-computed options for the user' },
        },
      },
      priority: {
        type: 'string',
        enum: ['low', 'normal', 'high', 'urgent'],
        default: 'normal',
      },
      timeout_seconds: {
        type: 'integer',
        default: 30,
        minimum: 10,
        maximum: 300,
      },
    },
  },
  handler: async (args: Record<string, unknown>) => {
    logger.info({ args }, 'create_call invoked');

    const result = await client.createCall(args as unknown as Parameters<typeof client.createCall>[0]);

    if (result.error) {
      return {
        content: [{ type: 'text', text: `Error: ${result.message ?? result.error}` }],
        isError: true,
      };
    }

    return {
      content: [{
        type: 'text',
        text: JSON.stringify({
          call_id: result.data!.call_id,
          status: result.data!.status,
          ...(result.data!.expires_at ? { expires_at: result.data!.expires_at } : {}),
        }, null, 2),
      }],
    };
  },
};

export const resumeTaskTool = {
  name: 'resume_task',
  description: 'Get the structured response from a completed call and resume execution',
  inputSchema: {
    type: 'object',
    required: ['call_id'],
    properties: {
      call_id: { type: 'string', description: 'Call identifier returned from create_call' },
      wait_for_completion: {
        type: 'boolean',
        default: false,
        description: 'If true, blocks until call is complete (long timeout)',
      },
    },
  },
  handler: async (args: Record<string, unknown>) => {
    const callId = args.call_id as string;
    const wait = args.wait_for_completion as boolean;

    let attempts = 0;
    const maxAttempts = wait ? 60 : 1;

    while (attempts < maxAttempts) {
      const result = await client.getCall(callId);

      if (result.error) {
        return {
          content: [{ type: 'text', text: `Error: ${result.message ?? result.error}` }],
          isError: true,
        };
      }

      const call = result.data!;

      if (call.status === 'ended' || call.status === 'cancelled' || call.status === 'timed_out') {
        return {
          content: [{
            type: 'text',
            text: JSON.stringify({
              status: call.status,
              result: call.result ?? null,
              duration_seconds: call.duration_seconds ?? null,
            }, null, 2),
          }],
        };
      }

      if (!wait) {
        return {
          content: [{
            type: 'text',
            text: JSON.stringify({ status: call.status, message: 'Call still in progress. Use wait_for_completion=true to block.' }, null, 2),
          }],
        };
      }

      await new Promise((r) => setTimeout(r, 2000));
      attempts++;
    }

    return {
      content: [{ type: 'text', text: JSON.stringify({ status: 'timeout', message: 'Call did not complete within the wait period' }, null, 2) }],
    };
  },
};

export const cancelCallTool = {
  name: 'cancel_call',
  description: 'Cancel a pending or active call',
  inputSchema: {
    type: 'object',
    required: ['call_id'],
    properties: {
      call_id: { type: 'string' },
      reason: {
        type: 'string',
        enum: ['resolved', 'timeout', 'error', 'user_requested'],
        default: 'resolved',
      },
    },
  },
  handler: async (args: Record<string, unknown>) => {
    const callId = args.call_id as string;
    const reason = (args.reason as string) ?? 'resolved';

    const result = await client.cancelCall(callId, reason);

    if (result.error) {
      return {
        content: [{ type: 'text', text: `Error: ${result.message ?? result.error}` }],
        isError: true,
      };
    }

    return {
      content: [{ type: 'text', text: JSON.stringify({ status: result.data!.status }, null, 2) }],
    };
  },
};

export const queryPresenceTool = {
  name: 'query_presence',
  description: 'Check if a user is currently available for voice calls',
  inputSchema: {
    type: 'object',
    required: ['user_id'],
    properties: {
      user_id: { type: 'string' },
    },
  },
  handler: async (args: Record<string, unknown>) => {
    const userId = args.user_id as string;
    const result = await client.queryPresence(userId);

    if (result.error) {
      return {
        content: [{ type: 'text', text: `Error: ${result.message ?? result.error}` }],
        isError: true,
      };
    }

    return {
      content: [{
        type: 'text',
        text: JSON.stringify(result.data!, null, 2),
      }],
    };
  },
};

export const notifyCompletionTool = {
  name: 'notify_completion',
  description: 'Send a non-urgent notification to a user that a task has completed',
  inputSchema: {
    type: 'object',
    required: ['user_id', 'summary'],
    properties: {
      user_id: { type: 'string' },
      summary: { type: 'string', maxLength: 500 },
      details: {
        type: 'object',
        properties: {
          task_id: { type: 'string' },
          duration_seconds: { type: 'integer' },
          artifacts: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                name: { type: 'string' },
                type: { type: 'string' },
                url: { type: 'string' },
              },
            },
          },
        },
      },
      priority: { type: 'string', enum: ['low', 'normal'], default: 'normal' },
    },
  },
  handler: async (args: Record<string, unknown>) => {
    const userId = args.user_id as string;
    const summary = args.summary as string;

    const result = await client.sendNotification(userId, 'task_complete', {
      summary,
      ...(args.details ? { details: args.details } : {}),
    });

    if (result.error) {
      return {
        content: [{ type: 'text', text: `Error: ${result.message ?? result.error}` }],
        isError: true,
      };
    }

    return {
      content: [{
        type: 'text',
        text: JSON.stringify({
          status: result.data!.status,
          notification_id: result.data!.device_targets > 0 ? 'delivered' : 'queued',
        }, null, 2),
      }],
    };
  },
};

export const tools = [
  createCallTool,
  resumeTaskTool,
  cancelCallTool,
  queryPresenceTool,
  notifyCompletionTool,
];
