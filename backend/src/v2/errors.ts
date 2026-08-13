/** Structured v2 errors — HTTP status + `code` per the v2 API spec §1. */
export class V2ApiError extends Error {
  constructor(
    readonly statusCode: number,
    readonly code: string,
    message: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = 'V2ApiError';
  }
}

export class CallNotFoundError extends V2ApiError {
  constructor(callId: string) {
    super(404, 'NOT_FOUND', `Call not found: ${callId}`);
    this.name = 'CallNotFoundError';
  }
}

export class EventNotFoundError extends V2ApiError {
  constructor() {
    super(404, 'EVENT_NOT_FOUND', 'Event not found');
    this.name = 'EventNotFoundError';
  }
}

export class ForbiddenError extends V2ApiError {
  constructor(message: string) {
    super(403, 'FORBIDDEN', message);
    this.name = 'ForbiddenError';
  }
}

export class ValidationError extends V2ApiError {
  constructor(message: string, details?: unknown) {
    super(400, 'VALIDATION_ERROR', message, details);
    this.name = 'ValidationError';
  }
}
