import { logger } from '../../common/logger.js';
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
  CALL_ABORTED,
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
  CallAbortedPayload,
} from './events.js';

export function registerCallSubscribers(eventBus: EventBus): void {
  eventBus.subscribe<CallCreatedPayload>(
    CALL_CREATED,
    async (event) => {
      const { userId, callId } = event.payload;
      logger.info({ userId, callId }, '[EventBus] CallCreated received — mirroring call creation');
    },
    { name: 'calls.created-validator', scope: 'calls' },
  );

  eventBus.subscribe<CallAnsweredPayload>(
    CALL_ANSWERED,
    async (event) => {
      const { userId, callId } = event.payload;
      logger.info({ userId, callId }, '[EventBus] CallAnswered received');
    },
    { name: 'calls.answered-logger', scope: 'calls' },
  );

  eventBus.subscribe<CallPausedPayload>(
    CALL_PAUSED,
    async (event) => {
      const { userId, callId, delayMinutes } = event.payload;
      logger.info({ userId, callId, delayMinutes }, '[EventBus] CallPaused received');
    },
    { name: 'calls.paused-logger', scope: 'calls' },
  );

  eventBus.subscribe<CallEndedPayload>(
    CALL_ENDED,
    async (event) => {
      const { userId, callId } = event.payload;
      logger.info({ userId, callId }, '[EventBus] CallEnded received');
    },
    { name: 'calls.ended-logger', scope: 'calls' },
  );

  eventBus.subscribe<CallCancelledPayload>(
    CALL_CANCELLED,
    async (event) => {
      const { userId, callId } = event.payload;
      logger.info({ userId, callId }, '[EventBus] CallCancelled received');
    },
    { name: 'calls.cancelled-logger', scope: 'calls' },
  );

  eventBus.subscribe<CallResumedPayload>(
    CALL_RESUMED,
    async (event) => {
      const { userId, callId, delayMinutes } = event.payload;
      logger.info({ userId, callId, delayMinutes }, '[EventBus] CallResumed received');
    },
    { name: 'calls.resumed-logger', scope: 'calls' },
  );

  eventBus.subscribe<CallDeletedPayload>(
    CALL_DELETED,
    async (event) => {
      const { userId, callId, statusAtDeletion } = event.payload;
      logger.info({ userId, callId, statusAtDeletion }, '[EventBus] CallDeleted received');
    },
    { name: 'calls.deleted-logger', scope: 'calls' },
  );

  eventBus.subscribe<CallExpiredPayload>(
    CALL_EXPIRED,
    async (event) => {
      const { userId, callId, reason } = event.payload;
      logger.info({ userId, callId, reason }, '[EventBus] CallExpired received');
    },
    { name: 'calls.expired-logger', scope: 'calls' },
  );

  eventBus.subscribe<CallAbortedPayload>(
    CALL_ABORTED,
    async (event) => {
      const { userId, callId, reason } = event.payload;
      logger.info({ userId, callId, reason }, '[EventBus] CallAborted received');
    },
    { name: 'calls.aborted-logger', scope: 'calls' },
  );
}
