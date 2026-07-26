export class EventBusError extends Error {
  public readonly code: string;

  constructor(message: string, code?: string) {
    super(message);
    this.name = 'EventBusError';
    this.code = code ?? 'EVENT_BUS_ERROR';
  }
}
