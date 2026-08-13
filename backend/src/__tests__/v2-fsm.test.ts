import { describe, it, expect } from 'vitest';
import { transition, InvalidTransitionError, isOpenState, TERMINAL_STATES } from '../v2/call-fsm.js';
import type { V2CallState } from '../v2/call-fsm.js';

describe('v2 call FSM', () => {
  it('walks the happy path creating → ringing → connecting → connected → completed', () => {
    let state: V2CallState = 'creating';
    expect(isOpenState(state)).toBe(true);
    state = transition(state, 'ring');
    expect(state).toBe('ringing');
    state = transition(state, 'answer');
    expect(state).toBe('connecting');
    state = transition(state, 'connect');
    expect(state).toBe('connected');
    state = transition(state, 'complete');
    expect(state).toBe('completed');
    expect(isOpenState(state)).toBe(false);
  });

  it('rejects commands from an incompatible state', () => {
    expect(() => transition('creating', 'answer')).toThrow(InvalidTransitionError);
    expect(() => transition('creating', 'message')).toThrow(InvalidTransitionError);
    expect(() => transition('ringing', 'connect')).toThrow(InvalidTransitionError);
    expect(() => transition('paused', 'connect')).toThrow(InvalidTransitionError);
  });

  it('allows messages and utterances on every open state but not before ring', () => {
    for (const state of ['ringing', 'connecting', 'connected', 'paused'] as V2CallState[]) {
      expect(transition(state, 'message')).toBe(state);
      expect(transition(state, 'utterance')).toBe(state);
    }
    expect(() => transition('creating', 'message')).toThrow(InvalidTransitionError);
  });

  it('supports pause/resume and completes from any open state', () => {
    expect(transition('connected', 'pause')).toBe('paused');
    expect(transition('paused', 'resume')).toBe('connected');
    for (const state of ['creating', 'ringing', 'connecting', 'connected', 'paused'] as V2CallState[]) {
      expect(transition(state, 'complete')).toBe('completed');
      expect(transition(state, 'fail')).toBe('failed');
    }
  });

  it('treats terminal states as absorbing with idempotent same-command no-ops', () => {
    for (const terminal of TERMINAL_STATES) {
      expect(isOpenState(terminal)).toBe(false);
    }
    expect(transition('completed', 'complete')).toBe('completed');
    expect(transition('failed', 'fail')).toBe('failed');
    expect(() => transition('completed', 'message')).toThrow(InvalidTransitionError);
    expect(() => transition('failed', 'ring')).toThrow(InvalidTransitionError);
  });
});
