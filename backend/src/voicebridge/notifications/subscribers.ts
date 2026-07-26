import { logger } from '../../common/logger.js';
import type { EventBus } from '../../event-bus/index.js';
import {
  NOTIFICATION_REQUESTED,
  NOTIFICATION_DELIVERED,
  NOTIFICATION_FAILED,
} from './events.js';
import type {
  NotificationRequestedPayload,
  NotificationDeliveredPayload,
  NotificationFailedPayload,
} from './events.js';

export function registerNotificationSubscribers(eventBus: EventBus): void {
  eventBus.subscribe<NotificationRequestedPayload>(
    NOTIFICATION_REQUESTED,
    async (event) => {
      const { userId, notificationType, payload } = event.payload;
      logger.info(
        { userId, notificationType, payloadType: payload.type },
        '[EventBus] NotificationRequested received — mirroring validation',
      );
    },
    { name: 'notifications.requested-validator', scope: 'notifications' },
  );

  eventBus.subscribe<NotificationDeliveredPayload>(
    NOTIFICATION_DELIVERED,
    async (event) => {
      const { userId, notificationType } = event.payload;
      logger.info(
        { userId, notificationType },
        '[EventBus] NotificationDelivered received',
      );
    },
    { name: 'notifications.delivered-logger', scope: 'notifications' },
  );

  eventBus.subscribe<NotificationFailedPayload>(
    NOTIFICATION_FAILED,
    async (event) => {
      const { userId, notificationType, error } = event.payload;
      logger.warn(
        { userId, notificationType, error },
        '[EventBus] NotificationFailed received',
      );
    },
    { name: 'notifications.failed-logger', scope: 'notifications' },
  );
}
