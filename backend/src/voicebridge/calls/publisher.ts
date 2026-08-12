import { createEventPublisher } from '../../common/event-publisher.js';
import type { EventBus } from '../../event-bus/index.js';
import {
  CALL_CREATED,
  CALL_ANSWERED,
  CALL_PAUSED,
  CALL_ENDED,
  CALL_CANCELLED,
  CALL_RESUMED,
  CALL_DELETED,
  CALL_EXPIRED,
  CALL_DELAYED,
} from './events.js';
import type {
  CallCreatedPayload,
  CallAnsweredPayload,
  CallPausedPayload,
  CallEndedPayload,
  CallCancelledPayload,
  CallResumedPayload,
  CallDeletedPayload,
  CallExpiredPayload,
  CallDelayedPayload,
} from './events.js';

const publisher = createEventPublisher('voicebridge.calls', 1);

export function install(eventBus: EventBus): void {
  publisher.install(eventBus);
}

export const publishCallCreated = (userId: string, callId: string): void =>
  publisher.publish<CallCreatedPayload>(CALL_CREATED, { userId, callId });

export const publishCallAnswered = (userId: string, callId: string): void =>
  publisher.publish<CallAnsweredPayload>(CALL_ANSWERED, { userId, callId });

export const publishCallPaused = (
  userId: string,
  callId: string,
  delayMinutes: number,
  resumeAt: string,
): void =>
  publisher.publish<CallPausedPayload>(CALL_PAUSED, { userId, callId, delayMinutes, resumeAt });

export const publishCallEnded = (userId: string, callId: string): void =>
  publisher.publish<CallEndedPayload>(CALL_ENDED, { userId, callId });

export const publishCallCancelled = (userId: string, callId: string): void =>
  publisher.publish<CallCancelledPayload>(CALL_CANCELLED, { userId, callId });

export const publishCallResumed = (
  userId: string,
  callId: string,
  delayMinutes: number,
  resumeAt: string,
): void =>
  publisher.publish<CallResumedPayload>(CALL_RESUMED, { userId, callId, delayMinutes, resumeAt });

export const publishCallDeleted = (
  userId: string,
  callId: string,
  statusAtDeletion: string,
  retentionMs: number,
): void =>
  publisher.publish<CallDeletedPayload>(CALL_DELETED, { userId, callId, statusAtDeletion, retentionMs });

export const publishCallExpired = (
  userId: string,
  callId: string,
  reason: string,
  pausedDurationMinutes: number,
): void =>
  publisher.publish<CallExpiredPayload>(CALL_EXPIRED, { userId, callId, reason, pausedDurationMinutes });

export const publishCallDelayed = (
  userId: string,
  callId: string,
  reason: CallDelayedPayload['reason'],
  attemptsLeft: number,
): void =>
  publisher.publish<CallDelayedPayload>(CALL_DELAYED, { userId, callId, reason, attemptsLeft });
