export const NOTIFICATION_REQUESTED = 'notification.requested';
export const NOTIFICATION_DELIVERED = 'notification.delivered';
export const NOTIFICATION_FAILED = 'notification.failed';
export const NOTIFICATION_EVENT_VERSION = 1;

export interface NotificationRequestedPayload {
  userId: string;
  notificationType: string;
  payload: Record<string, unknown>;
}

export interface NotificationDeliveredPayload {
  userId: string;
  notificationType: string;
}

export interface NotificationFailedPayload {
  userId: string;
  notificationType: string;
  error: string;
}
