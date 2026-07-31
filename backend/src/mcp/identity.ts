import { AsyncLocalStorage } from 'node:async_hooks';
import { DEFAULT_AGENT_NAME } from '../voicebridge/ai-keys.js';

export interface McpIdentity {
  agentName: string;
  keyId?: string;
  via: 'service' | 'ai_key' | 'phone';
}

export const mcpIdentityStorage = new AsyncLocalStorage<McpIdentity>();

export function getAgentIdentity(): McpIdentity {
  return mcpIdentityStorage.getStore() ?? { agentName: DEFAULT_AGENT_NAME, via: 'service' };
}
