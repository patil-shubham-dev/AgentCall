/**
 * Media provider seam (roadmap M2 — "streaming TTS token→audio"). The engine
 * never talks to a vendor SDK; it drives a `TtsProvider` and reacts to its
 * callbacks, so a provider swap is config, not code (roadmap §3.2 R2/R10).
 *
 * M2 ships with:
 *  - `SyncTtsProvider` — the $0 default. "On-device TTS" means the phone turns
 *    text into audio; the platform only tracks lifecycle boundaries, and this
 *    provider completes a message in one synchronous pass (identical event
 *    sequence to the M1 engine).
 *  - `ScriptedTtsProvider` — deterministic token stream for tests: barge-in
 *    latency, TTFB, and message.failed paths are asserted with fixed timing,
 *    never flaky wall-clock races.
 *
 * The audio transport itself (WS media channel, WebRTC attach) is M4; M2 owns
 * the lifecycle contract (`message.started` on first audio byte boundary,
 * `message.completed`/`message.failed` at the end) plus the hard-cut budget.
 */

export interface TtsToken {
  text: string;
  /** TTS audio duration for this token, in ms (0 when unknown). */
  audio_ms: number;
}

export interface TtsStats {
  duration_ms: number;
  chars_spoken: number;
  audio_bytes: number;
}

export interface TtsSpeakInput {
  messageId: string;
  content: string;
  voice?: string;
}

export interface TtsCallbacks {
  /** First audio byte boundary (TTFB) — the engine emits message.started. */
  onStarted(messageId: string, firstToken: TtsToken): void;
  /** Each streamed token (pacing only; the engine emits no per-token event). */
  onToken(messageId: string, token: TtsToken): void;
  onDone(messageId: string, stats: TtsStats): void;
  onError(messageId: string, reason: string): void;
}

export interface TtsHandle {
  /** Synchronous hard cut — the barge-in budget (p95 ≤ 50 ms) lives here. */
  stop(): void;
  readonly stopped: boolean;
  /** Token/audio streamed so far (for interrupted_audio_ms). */
  readonly stats: { chars_streamed: number; audio_ms_streamed: number };
}

export interface TtsProvider {
  readonly name: string;
  /**
   * Begin streaming `content` as TTS. The provider may emit synchronously
   * (sync provider) or asynchronously (scripted/cloud); it must be safe to
   * call `stop()` from any callback (including the engine's barge-in path).
   */
  speak(input: TtsSpeakInput, callbacks: TtsCallbacks): TtsHandle;
}

/**
 * $0 default: the phone's on-device TTS owns the audio. The platform records
 * the lifecycle in one pass — queued → started (first byte) → completed —
 * matching the M1 engine's observable behavior byte-for-byte.
 */
export class SyncTtsProvider implements TtsProvider {
  readonly name = 'on-device';

  speak(input: TtsSpeakInput, callbacks: TtsCallbacks): TtsHandle {
    let stopped = false;
    let chars = 0;
    let audioMs = 0;
    const token: TtsToken = { text: input.content, audio_ms: 0 };
    const emitStarted = (): void => {
      chars = token.text.length;
      audioMs = token.audio_ms;
      callbacks.onStarted(input.messageId, token);
      callbacks.onToken(input.messageId, token);
    };
    emitStarted();
    callbacks.onDone(input.messageId, {
      duration_ms: 0,
      chars_spoken: input.content.length,
      audio_bytes: 0,
    });
    return {
      stop(): void {
        stopped = true;
      },
      get stopped(): boolean {
        return stopped;
      },
      get stats(): { chars_streamed: number; audio_ms_streamed: number } {
        return { chars_streamed: chars, audio_ms_streamed: audioMs };
      },
    };
  }
}

export interface ScriptedTokenSpec {
  text: string;
  audio_ms: number;
  delay_ms: number;
}

/**
 * Deterministic streaming provider for tests. The engine must observe
 * `onStarted` (first token) → `onToken`* → `onDone` exactly as scripted, in
 * wall-clock terms bounded by the scripted delays — so latency assertions
 * (TTFB, barge-in cut) are stable, never flaky.
 */
export class ScriptedTtsProvider implements TtsProvider {
  readonly name = 'scripted';

  constructor(
    private readonly tokens: ScriptedTokenSpec[],
    private readonly errorAfter?: { reason: string },
  ) {}

  speak(input: TtsSpeakInput, callbacks: TtsCallbacks): TtsHandle {
    let stopped = false;
    let chars = 0;
    let audioMs = 0;
    let tokenTimer: NodeJS.Timeout | null = null;

    const handle: TtsHandle = {
      stop(): void {
        stopped = true;
        if (tokenTimer) clearTimeout(tokenTimer);
      },
      get stopped(): boolean {
        return stopped;
      },
      get stats(): { chars_streamed: number; audio_ms_streamed: number } {
        return { chars_streamed: chars, audio_ms_streamed: audioMs };
      },
    };

    let index = 0;
    const emitToken = (): void => {
      if (stopped || index >= this.tokens.length) return;
      const spec = this.tokens[index];
      if (spec === undefined) return;
      index++;
      chars += spec.text.length;
      audioMs += spec.audio_ms;
      if (index === 1) {
        callbacks.onStarted(input.messageId, { text: spec.text, audio_ms: spec.audio_ms });
      } else {
        callbacks.onToken(input.messageId, { text: spec.text, audio_ms: spec.audio_ms });
      }
      if (index === this.tokens.length) {
        if (this.errorAfter !== undefined) {
          callbacks.onError(input.messageId, this.errorAfter.reason);
          return;
        }
        callbacks.onDone(input.messageId, {
          duration_ms: audioMs,
          chars_spoken: chars,
          audio_bytes: Math.round((audioMs / 1000) * 16_000 * 2), // 16 kHz PCM16
        });
        return;
      }
      const next = this.tokens[index];
      tokenTimer = setTimeout(emitToken, next?.delay_ms ?? 0);
      tokenTimer.unref?.();
    };
    emitToken();
    return handle;
  }
}
