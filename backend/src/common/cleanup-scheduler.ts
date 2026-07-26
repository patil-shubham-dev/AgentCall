export interface PendingCleanup {
  id: string;
  executeAt: number;
  remainingMs: number;
}

export class CleanupScheduler {
  private tasks = new Map<string, { executeAt: number; callback: () => void; timer: NodeJS.Timeout }>();
  private shutDown = false;

  schedule(id: string, executeAt: number | Date, callback: () => void): void {
    if (this.shutDown) return;
    this.cancel(id);

    const at = typeof executeAt === 'number' ? executeAt : executeAt.getTime();
    const delay = Math.max(0, at - Date.now());

    const timer = setTimeout(() => {
      if (this.shutDown) return;
      this.tasks.delete(id);
      callback();
    }, delay);
    timer.unref();

    this.tasks.set(id, { executeAt: at, callback, timer });
  }

  cancel(id: string): boolean {
    const existing = this.tasks.get(id);
    if (!existing) return false;
    clearTimeout(existing.timer);
    this.tasks.delete(id);
    return true;
  }

  has(id: string): boolean {
    return this.tasks.has(id);
  }

  pending(): PendingCleanup[] {
    const now = Date.now();
    const result: PendingCleanup[] = [];
    for (const [id, task] of this.tasks) {
      result.push({ id, executeAt: task.executeAt, remainingMs: Math.max(0, task.executeAt - now) });
    }
    return result.sort((a, b) => a.executeAt - b.executeAt);
  }

  shutdown(): void {
    this.shutDown = true;
    for (const [, task] of this.tasks) {
      clearTimeout(task.timer);
    }
    this.tasks.clear();
  }
}
