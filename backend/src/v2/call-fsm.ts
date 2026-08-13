/**
 * Validated finite state machine for a v2 call (docs/v2/01-architecture.md §5).
 * Pure: no I/O, no timers — the engine drives it and applies side effects on
 * transition. Terminal states (completed/failed) are absorbing.
 */

export type V2CallState =
  | 'creating' // session persisted, nothing else yet
  | 'ringing' // device notified (call_incoming equivalent)
  | 'connecting' // human tapped answer
  | 'connected' // media established
  | 'paused' // callback-style pause
  | 'completed' // terminal, resolved
  | 'failed'; // terminal, unrecoverable

export type V2CallCommand =
  | 'ring' // creating -> ringing (device push)
  | 'answer' // ringing -> connecting
  | 'connect' // connecting -> connected (media up)
  | 'message' // AI message (no state change; allowed while open)
  | 'utterance' // user text (no state change; allowed while open)
  | 'pause' // connected -> paused
  | 'resume' // paused -> connected
  | 'complete' // any open state -> completed (hangup is THE terminal command)
  | 'fail'; // any open state -> failed

/** States where the call is live and commands other than terminal are valid. */
export const OPEN_STATES: readonly V2CallState[] = [
  'creating',
  'ringing',
  'connecting',
  'connected',
  'paused',
];

export const TERMINAL_STATES: readonly V2CallState[] = ['completed', 'failed'];

export function isOpenState(state: V2CallState): boolean {
  return (OPEN_STATES as readonly string[]).includes(state);
}

export class InvalidTransitionError extends Error {
  readonly code = 'INVALID_TRANSITION';
  /** Maps to HTTP 409 per the v2 API spec, also honored by the global handler. */
  readonly statusCode = 409;
  constructor(
    readonly from: V2CallState,
    readonly command: V2CallCommand,
  ) {
    super(`Invalid transition: ${command} from state ${from}`);
    this.name = 'InvalidTransitionError';
  }
}

type TransitionTable = Record<V2CallState, Partial<Record<V2CallCommand, V2CallState>>>;

const TRANSITIONS: TransitionTable = {
  creating: {
    ring: 'ringing',
    complete: 'completed',
    fail: 'failed',
  },
  ringing: {
    answer: 'connecting',
    ring: 'ringing', // re-ring / no-op
    message: 'ringing',
    utterance: 'ringing',
    complete: 'completed',
    fail: 'failed',
  },
  connecting: {
    connect: 'connected',
    message: 'connecting',
    utterance: 'connecting',
    complete: 'completed',
    fail: 'failed',
  },
  connected: {
    message: 'connected',
    utterance: 'connected',
    pause: 'paused',
    complete: 'completed',
    fail: 'failed',
  },
  paused: {
    resume: 'connected',
    message: 'paused',
    utterance: 'paused',
    complete: 'completed',
    fail: 'failed',
  },
  completed: {
    complete: 'completed', // idempotent terminal no-op (retries)
  },
  failed: {
    fail: 'failed', // idempotent terminal no-op
  },
};

/**
 * Returns the next state for `command` from `current`. Idempotent transitions
 * (re-answer, re-hangup, message on an open call) return the same state.
 * Throws InvalidTransitionError for anything else (maps to HTTP 409).
 */
export function transition(current: V2CallState, command: V2CallCommand): V2CallState {
  const next = TRANSITIONS[current][command];
  if (next === undefined) {
    throw new InvalidTransitionError(current, command);
  }
  return next;
}

/** States reachable from `current` (for tests / diagnostics). */
export function reachableStates(current: V2CallState): V2CallCommand[] {
  return Object.keys(TRANSITIONS[current]) as V2CallCommand[];
}
