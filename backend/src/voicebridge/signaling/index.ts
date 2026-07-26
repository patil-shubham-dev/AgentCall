import type { EventBus } from '../../event-bus/index.js';
import { install } from './publisher.js';
import { registerSignalingSubscribers } from './subscribers.js';

export function register(eventBus: EventBus): void {
  install(eventBus);
  registerSignalingSubscribers(eventBus);
}

export {
  publishSignalingConnected,
  publishSignalingDisconnected,
  publishSignalingMessageReceived,
  publishSignalingFailed,
} from './publisher.js';

export type {
  SignalingConnectedPayload,
  SignalingDisconnectedPayload,
  SignalingMessageReceivedPayload,
  SignalingFailedPayload,
} from './events.js';
