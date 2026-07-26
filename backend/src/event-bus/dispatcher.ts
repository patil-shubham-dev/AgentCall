import type {
  Event,
  PublishResult,
  PublishHandlerError,
  BeforeEventHook,
  AfterEventHook,
  ErrorHook,
} from './types.js';
import { SubscriberRegistry } from './registry.js';

export class EventDispatcher {
  private beforeHooks: BeforeEventHook[] = [];
  private afterHooks: AfterEventHook[] = [];
  private errorHooks: ErrorHook[] = [];

  constructor(
    private registry: SubscriberRegistry,
    private defaultTimeoutMs: number = 0,
  ) {}

  addBeforeHook(hook: BeforeEventHook): void {
    this.beforeHooks.push(hook);
  }

  addAfterHook(hook: AfterEventHook): void {
    this.afterHooks.push(hook);
  }

  addErrorHook(hook: ErrorHook): void {
    this.errorHooks.push(hook);
  }

  removeBeforeHook(hook: BeforeEventHook): void {
    this.beforeHooks = this.beforeHooks.filter((h) => h !== hook);
  }

  removeAfterHook(hook: AfterEventHook): void {
    this.afterHooks = this.afterHooks.filter((h) => h !== hook);
  }

  removeErrorHook(hook: ErrorHook): void {
    this.errorHooks = this.errorHooks.filter((h) => h !== hook);
  }

  clearHooks(): void {
    this.beforeHooks = [];
    this.afterHooks = [];
    this.errorHooks = [];
  }

  async dispatch<T>(event: Event<T>): Promise<PublishResult> {
    const entries = this.registry.get(event.type);
    const syncHandlers = entries.filter((e) => !e.async);
    const asyncHandlers = entries.filter((e) => e.async);

    await this.notifyBeforeHooks(event);

    const errors = await this.runSyncHandlers(syncHandlers, event);
    const asyncErrorsPromise = this.scheduleAsyncHandlers(asyncHandlers, event);

    const result: PublishResult = {
      eventId: event.metadata.eventId,
      type: event.type,
      syncHandlerCount: syncHandlers.length,
      asyncHandlerCount: asyncHandlers.length,
      errors,
      asyncErrors: asyncErrorsPromise,
    };

    await this.notifyAfterHooks(event, result);

    return result;
  }

  private async runSyncHandlers<T>(
    entries: ReturnType<SubscriberRegistry['get']>,
    event: Event<T>,
  ): Promise<PublishHandlerError[]> {
    const errors: PublishHandlerError[] = [];

    for (const entry of entries) {
      try {
        const timeoutMs = entry.timeoutMs || this.defaultTimeoutMs;
        if (timeoutMs > 0) {
          await this.executeWithTimeout(entry.handler, event, timeoutMs);
        } else {
          await entry.handler(event);
        }
      } catch (err) {
        const error = err instanceof Error ? err : new Error(String(err));
        errors.push({ handlerName: entry.name, error });
        await this.notifyErrorHooks(error, event);
      }
    }

    return errors;
  }

  private scheduleAsyncHandlers<T>(
    entries: ReturnType<SubscriberRegistry['get']>,
    event: Event<T>,
  ): Promise<PublishHandlerError[]> {
    if (entries.length === 0) return Promise.resolve([]);

    const promises = entries.map((entry) => {
      return new Promise<PublishHandlerError[]>((resolve) => {
        queueMicrotask(async () => {
          try {
            await entry.handler(event);
            resolve([]);
          } catch (err) {
            const error = err instanceof Error ? err : new Error(String(err));
            await this.notifyErrorHooks(error, event);
            resolve([{ handlerName: entry.name, error }]);
          }
        });
      });
    });

    return Promise.all(promises).then((results) => results.flat());
  }

  private executeWithTimeout<T>(
    handler: (event: Event<T>) => Promise<void> | void,
    event: Event<T>,
    timeoutMs: number,
  ): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`Handler timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      Promise.resolve(handler(event))
        .then(() => {
          clearTimeout(timer);
          resolve();
        })
        .catch((err) => {
          clearTimeout(timer);
          reject(err);
        });
    });
  }

  private async notifyBeforeHooks(event: Event): Promise<void> {
    for (const hook of this.beforeHooks) {
      try {
        await hook(event);
      } catch {
        /* lifecycle hooks must not affect dispatch */
      }
    }
  }

  private async notifyAfterHooks(
    event: Event,
    result: PublishResult,
  ): Promise<void> {
    for (const hook of this.afterHooks) {
      try {
        await hook(event, result);
      } catch {
        /* lifecycle hooks must not affect dispatch */
      }
    }
  }

  private async notifyErrorHooks(error: Error, event: Event): Promise<void> {
    for (const hook of this.errorHooks) {
      try {
        await hook(error, event);
      } catch {
        /* lifecycle hooks must not affect dispatch */
      }
    }
  }
}
