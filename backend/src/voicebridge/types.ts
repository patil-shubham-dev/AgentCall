import type { CallPriority, CallReason } from '../common/types.js';

export type MessageRole = 'ai' | 'user' | 'system';
export type MessageType = 'text' | 'audio' | 'system';
export type CallStatus = 'pending' | 'active' | 'paused' | 'completed' | 'cancelled' | 'aborted';

export interface VoiceMessage {
  id: string;
  role: MessageRole;
  type: MessageType;
  content: string;
  clientMessageId?: string;
  audioUrl?: string;
  audioDurationMs?: number;
  createdAt: string;
}

export interface ClientInfo {
  name: string;
  version?: string;
}

export interface VoiceCallSession {
  id: string;
  userId: string;
  agentId: string;
  status: CallStatus;
  priority: CallPriority;
  reason: CallReason;
  context: {
    taskId?: string;
    summary: string;
    options?: string[];
  };
  /** The MCP client/harness that created the call (ChatGPT, Claude, ...). */
  clientInfo?: ClientInfo;
  messages: VoiceMessage[];
  result?: {
    transcriptSummary?: string;
    userResponse?: string;
    decision?: string;
    selectedOption?: string;
    sentiment?: string;
    actionItems?: string[];
  };
  createdAt: string;
  /** Set when ring dispatch succeeded (WS or FCM). After this point MCP disconnect must not abort. */
  ringDispatchedAt?: string;
  /**
   * Durable ai-wait fact: mirrors the in-memory aiWaitLeases entry
   * (VoiceBridgeService.registerAiWait) so a restart doesn't silently drop
   * "this call was mid-wait". The wake plumbing (sessionChangeWaiters,
   * counters) stays in-process only — it is fine to lose on crash; only the
   * fact of an active wait + deadline is persisted, on the existing session
   * row (sessions.data JSONB carries the whole object, no DDL needed).
   */
  aiWaitActiveUntil?: string | null;
  aiWaitCount?: number;
  aiWaitLastActiveAt?: string;
  lastActivityAt?: string;
  connectedAt?: string;
  pausedAt?: string;
  resumedAt?: string;
  completedAt?: string;
  retentionExpiresAt?: string;
}

export interface CreateCallInput {
  userId: string;
  agentId: string;
  reason: CallReason;
  summary: string;
  taskId?: string;
  options?: string[];
  priority?: CallPriority;
  /** MCP client that requested the call (name/version from initialize). */
  clientInfo?: ClientInfo;
}


export interface CallbackRequest {
  callId: string;
  delayMinutes: number;
  reason: string;
  note?: string;
}
