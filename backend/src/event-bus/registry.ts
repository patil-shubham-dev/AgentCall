import type { EventHandler, SubscribeOptions } from './types.js';

interface HandlerEntry<T = unknown> {
  handler: EventHandler<T>;
  async: boolean;
  priority: number;
  timeoutMs: number;
  name: string;
  scope: string;
  id: symbol;
  eventType: string;
}

export interface AddResult {
  id: symbol;
  eventType: string;
}

export class SubscriberRegistry {
  private handlers = new Map<string, HandlerEntry[]>();
  private idToEntry = new Map<symbol, HandlerEntry>();
  private scopeToIds = new Map<string, Set<symbol>>();
  private nameCounters = new Map<string, number>();

  add<T>(
    eventType: string,
    handler: EventHandler<T>,
    options?: SubscribeOptions,
  ): AddResult {
    const name = this.resolveName(eventType, options?.name, handler.name);
    const scope = options?.scope ?? '';
    const id = Symbol('handler');

    const entry: HandlerEntry<T> = {
      handler,
      async: options?.async ?? false,
      priority: options?.priority ?? 0,
      timeoutMs: options?.timeoutMs ?? 0,
      name,
      scope,
      id,
      eventType,
    };

    const existing = this.handlers.get(eventType) ?? [];
    const insertIdx = this.findInsertIndex(existing, entry.priority);
    existing.splice(insertIdx, 0, entry as HandlerEntry);
    this.handlers.set(eventType, existing);

    this.idToEntry.set(id, entry as HandlerEntry);
    if (scope) {
      const ids = this.scopeToIds.get(scope) ?? new Set();
      ids.add(id);
      this.scopeToIds.set(scope, ids);
    }

    return { id, eventType };
  }

  remove(id: symbol): boolean {
    const entry = this.idToEntry.get(id);
    if (!entry) return false;

    const entries = this.handlers.get(entry.eventType);
    if (entries) {
      const idx = entries.findIndex((e) => e.id === id);
      if (idx !== -1) {
        entries.splice(idx, 1);
        if (entries.length === 0) this.handlers.delete(entry.eventType);
      }
    }

    this.idToEntry.delete(id);
    if (entry.scope) {
      const ids = this.scopeToIds.get(entry.scope);
      if (ids) {
        ids.delete(id);
        if (ids.size === 0) this.scopeToIds.delete(entry.scope);
      }
    }

    return true;
  }

  removeScope(scope: string): number {
    const ids = this.scopeToIds.get(scope);
    if (!ids) return 0;
    const entries = [...ids];
    let count = 0;
    for (const id of entries) {
      if (this.remove(id)) count++;
    }
    return count;
  }

  get(eventType: string): HandlerEntry[] {
    const result = this.handlers.get(eventType);
    return result ?? [];
  }

  clear(): void {
    this.handlers.clear();
    this.idToEntry.clear();
    this.scopeToIds.clear();
    this.nameCounters.clear();
  }

  get size(): number {
    return this.idToEntry.size;
  }

  hasEntries(): boolean {
    return this.idToEntry.size > 0;
  }

  private resolveName(
    eventType: string,
    explicitName: string | undefined,
    fnName: string,
  ): string {
    if (explicitName) return explicitName;
    if (fnName) return fnName;
    const count = (this.nameCounters.get(eventType) ?? 0) + 1;
    this.nameCounters.set(eventType, count);
    return `${eventType}:handler:${count}`;
  }

  private findInsertIndex(
    entries: HandlerEntry[],
    priority: number,
  ): number {
    for (let i = 0; i < entries.length; i++) {
      const entry = entries[i];
      if (entry && entry.priority < priority) return i;
    }
    return entries.length;
  }
}
