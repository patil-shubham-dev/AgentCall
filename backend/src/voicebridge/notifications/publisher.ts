import { createEventPublisher } from '../../common/event-publisher.js';
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

const publisher = createEventPublisher('voicebridge.notifications', 1);

export function install(eventBus: EventBus): void {
  publisher.install(eventBus);
}

export const publishNotificationRequested = (
  userId: string,
  notificationType: string,
  payload: Record<string, unknown>,
): void =>
  publisher.publish<NotificationRequestedPayload>(NOTIFICATION_REQUESTED, {
    userId,
    notificationType,
    payload,
  });

export const publishNotificationDelivered = (
  userId: string,
  notificationType: string,
): void =>
  publisher.publish<NotificationDeliveredPayload>(NOTIFICATION_DELIVERED, {
    userId,
    notificationType,
  });

export const publishNotificationFailed = (
  userId: string,
  notificationType: string,
  error: string,
): void =>
  publisher.publish<NotificationFailedPayload>(NOTIFICATION_FAILED, {
    userId,
    notificationType,
    error,
  });
