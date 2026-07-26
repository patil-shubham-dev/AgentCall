export const SIGNALING_CONNECTED = 'signaling.connected';
export const SIGNALING_DISCONNECTED = 'signaling.disconnected';
export const SIGNALING_MESSAGE_RECEIVED = 'signaling.message_received';
export const SIGNALING_FAILED = 'signaling.failed';
export const SIGNALING_EVENT_VERSION = 1;

export interface SignalingConnectedPayload {
  userId: string;
}

export interface SignalingDisconnectedPayload {
  userId: string;
}

export interface SignalingMessageReceivedPayload {
  userId: string;
  messageType: string;
  size: number;
}

export interface SignalingFailedPayload {
  userId: string;
  reason: string;
}
