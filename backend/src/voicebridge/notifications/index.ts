import type { EventBus } from '../../event-bus/index.js';
import { install } from './publisher.js';
import { registerNotificationSubscribers } from './subscribers.js';

export function register(eventBus: EventBus): void {
  install(eventBus);
  registerNotificationSubscribers(eventBus);
}

export {
  publishNotificationRequested,
  publishNotificationDelivered,
  publishNotificationFailed,
} from './publisher.js';

export type {
  NotificationRequestedPayload,
  NotificationDeliveredPayload,
  NotificationFailedPayload,
} from './events.js';
