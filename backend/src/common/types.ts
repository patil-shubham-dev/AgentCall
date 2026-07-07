export type CallStatus =
  | 'requested'
  | 'ringing'
  | 'connecting'
  | 'connected'
  | 'ended'
  | 'cancelled'
  | 'timed_out'
  | 'failed';

export type CallPriority = 'low' | 'normal' | 'high' | 'urgent';

export type CallReason = 'clarification' | 'approval' | 'error' | 'input_required';

export type PresenceStatus = 'online' | 'away' | 'busy' | 'offline';

export type Platform = 'android' | 'ios' | 'web';

export type ParticipantRole = 'caller' | 'callee' | 'observer';

export interface CallContext {
  task_id?: string;
  reason: CallReason;
  summary: string;
  options?: string[];
}

export interface CallResult {
  transcript_summary?: string;
  user_response?: string;
  decision?: string;
  selected_option?: string;
  sentiment?: 'positive' | 'neutral' | 'negative' | 'urgent';
  action_items?: string[];
  full_transcript?: string;
  duration_seconds?: number;
}

export interface ICECandidate {
  candidate: string;
  sdpMid: string;
  sdpMLineIndex: number;
}

export interface SignalMessage {
  type: string;
  payload: Record<string, unknown>;
  timestamp: string;
}
