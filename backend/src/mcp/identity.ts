import { AsyncLocalStorage } from 'node:async_hooks';
import { DEFAULT_AGENT_NAME } from '../voicebridge/ai-keys.js';
import type { ClientInfo } from '../voicebridge/types.js';

export interface McpIdentity {
  agentName: string;
  keyId?: string;
  via: 'service' | 'ai_key' | 'phone';
  /** clientInfo captured from the MCP initialize handshake (ChatGPT, Claude...). */
  clientInfo?: ClientInfo;
}

export const mcpIdentityStorage = new AsyncLocalStorage<McpIdentity>();

export function getAgentIdentity(): McpIdentity {
  return mcpIdentityStorage.getStore() ?? { agentName: DEFAULT_AGENT_NAME, via: 'service' };
}
