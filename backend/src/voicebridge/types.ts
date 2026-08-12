import type { CallPriority, CallReason } from '../common/types.js';

export type MessageRole = 'ai' | 'user' | 'system';
export type MessageType = 'text' | 'audio' | 'system';
export type CallStatus = 'pending' | 'active' | 'paused' | 'completed' | 'cancelled';

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
  lastActivityAt?: string;
  connectedAt?: string;
  pausedAt?: string;
  resumedAt?: string;
  completedAt?: string;
  retentionExpiresAt?: string;
}

export type CallOrigin = 'agent' | 'user';

export interface CreateCallInput {
  userId: string;
  agentId: string;
  reason: CallReason;
  summary: string;
  taskId?: string;
  options?: string[];
  priority?: CallPriority;
  /**
   * Who initiated the call. 'user' = outbound call from the phone app:
   * the ring gate is skipped entirely — ringing the calling phone itself
   * would be wrong — and the call waits for the AI to answer.
   */
  origin?: CallOrigin;
}


export interface CallbackRequest {
  callId: string;
  delayMinutes: number;
  reason: string;
  note?: string;
}
