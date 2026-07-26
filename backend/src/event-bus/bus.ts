import type {
  Event,
  EventHandler,
  SubscribeOptions,
  Subscription,
  PublishResult,
  BeforeEventHook,
  AfterEventHook,
  ErrorHook,
} from './types.js';
import { SubscriberRegistry } from './registry.js';
import { EventDispatcher } from './dispatcher.js';

export interface EventBusOptions {
  defaultHandlerTimeoutMs?: number;
}

export interface EventBus {
  publish<T>(event: Event<T>): Promise<PublishResult>;
  subscribe<T>(
    eventType: string,
    handler: EventHandler<T>,
    options?: SubscribeOptions,
  ): Subscription;
  unsubscribeScope(scope: string): number;
  getSubscriberCount(eventType?: string): number;
  onBeforeEvent(hook: BeforeEventHook): void;
  onAfterEvent(hook: AfterEventHook): void;
  onError(hook: ErrorHook): void;
  shutdown(): Promise<void>;
  isShutdown(): boolean;
}

export class DefaultEventBus implements EventBus {
  private registry: SubscriberRegistry;
  private dispatcher: EventDispatcher;
  private isShutdownFlag = false;

  constructor(
    registry?: SubscriberRegistry,
    options?: EventBusOptions,
  ) {
    this.registry = registry ?? new SubscriberRegistry();
    this.dispatcher = new EventDispatcher(
      this.registry,
      options?.defaultHandlerTimeoutMs ?? 30_000,
    );
  }

  async publish<T>(event: Event<T>): Promise<PublishResult> {
    if (this.isShutdownFlag) {
      throw new Error(`EventBus is shut down: cannot publish ${event.type}`);
    }
    return this.dispatcher.dispatch(event);
  }

  subscribe<T>(
    eventType: string,
    handler: EventHandler<T>,
    options?: SubscribeOptions,
  ): Subscription {
    const { id } = this.registry.add(eventType, handler, options);
    let disposed = false;

    return {
      get eventType() {
        return eventType;
      },
      get disposed() {
        return disposed;
      },
      unsubscribe: () => {
        if (disposed) return;
        disposed = true;
        this.registry.remove(id);
      },
    };
  }

  unsubscribeScope(scope: string): number {
    return this.registry.removeScope(scope);
  }

  getSubscriberCount(eventType?: string): number {
    if (eventType) return this.registry.get(eventType).length;
    return this.registry.size;
  }

  onBeforeEvent(hook: BeforeEventHook): void {
    this.dispatcher.addBeforeHook(hook);
  }

  onAfterEvent(hook: AfterEventHook): void {
    this.dispatcher.addAfterHook(hook);
  }

  onError(hook: ErrorHook): void {
    this.dispatcher.addErrorHook(hook);
  }

  async shutdown(): Promise<void> {
    this.isShutdownFlag = true;
    this.registry.clear();
    this.dispatcher.clearHooks();
  }

  isShutdown(): boolean {
    return this.isShutdownFlag;
  }
}
