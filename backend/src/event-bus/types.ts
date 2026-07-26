export interface EventMetadata {
  eventId: string;
  timestamp: string;
  correlationId: string;
  causationId?: string;
  source: string;
}

export interface Event<T = unknown> {
  type: string;
  version: number;
  payload: T;
  metadata: EventMetadata;
}

export type EventHandler<T = unknown> = (event: Event<T>) => Promise<void> | void;

export interface SubscribeOptions {
  async?: boolean;
  /** Higher priority handlers execute first within the same event type. Default: 0. */
  priority?: number;
  name?: string;
  timeoutMs?: number;
  scope?: string;
}

export interface Subscription {
  readonly eventType: string;
  readonly disposed: boolean;
  unsubscribe(): void;
}

export interface PublishResult {
  eventId: string;
  type: string;
  syncHandlerCount: number;
  asyncHandlerCount: number;
  errors: PublishHandlerError[];
  asyncErrors: Promise<PublishHandlerError[]>;
}

export interface PublishHandlerError {
  handlerName: string;
  error: Error;
}

export interface BeforeEventHook {
  (event: Event): void | Promise<void>;
}

export interface AfterEventHook {
  (event: Event, result: Readonly<PublishResult>): void | Promise<void>;
}

export interface ErrorHook {
  (error: Error, event: Event): void | Promise<void>;
}
