export const PRESENCE_CONNECTED = 'presence.connected';
export const PRESENCE_DISCONNECTED = 'presence.disconnected';
export const PRESENCE_UPDATED = 'presence.updated';
export const PRESENCE_EVENT_VERSION = 1;

export interface PresenceConnectedPayload {
  userId: string;
}

export interface PresenceDisconnectedPayload {
  userId: string;
}

export interface PresenceUpdatedPayload {
  userId: string;
}
