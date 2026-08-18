export const CALL_CREATED = 'call.created';
export const CALL_ANSWERED = 'call.answered';
export const CALL_PAUSED = 'call.paused';
export const CALL_ENDED = 'call.ended';
export const CALL_CANCELLED = 'call.cancelled';
export const CALL_RESUMED = 'call.resumed';
export const CALL_DELETED = 'call.deleted';
export const CALL_EXPIRED = 'call.expired';
export const CALL_ABORTED = 'call.aborted';
export const CALL_DELAYED = 'call.delayed';
export const CALL_EVENT_VERSION = 1;

export interface CallCreatedPayload {
  userId: string;
  callId: string;
}

export interface CallAnsweredPayload {
  userId: string;
  callId: string;
}

export interface CallPausedPayload {
  userId: string;
  callId: string;
  delayMinutes: number;
  resumeAt: string;
}

export interface CallEndedPayload {
  userId: string;
  callId: string;
}

export interface CallCancelledPayload {
  userId: string;
  callId: string;
}

export interface CallResumedPayload {
  userId: string;
  callId: string;
  delayMinutes: number;
  resumeAt: string;
}

export interface CallDeletedPayload {
  userId: string;
  callId: string;
  statusAtDeletion: string;
  retentionMs: number;
}

export interface CallExpiredPayload {
  userId: string;
  callId: string;
  reason: string;
  pausedDurationMinutes: number;
}

export interface CallAbortedPayload {
  userId: string;
  callId: string;
  reason: string;
}

export type CallDelayReason = 'agent_offline' | 'agent_busy';

export interface CallDelayedPayload {
  userId: string;
  callId: string;
  reason: CallDelayReason;
  attemptsLeft: number;
}
