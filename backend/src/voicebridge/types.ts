import type { CallPriority, CallReason } from '../common/types.js';

export type MessageRole = 'ai' | 'user' | 'system';
export type MessageType = 'text' | 'audio' | 'system';
export type CallStatus = 'pending' | 'active' | 'paused' | 'completed' | 'cancelled';
export type EmotionTag = 'calm' | 'urgent' | 'excited' | 'thoughtful' | 'neutral';
export type BargeInAction = 'stop' | 'rephrase' | 'wait' | 'callback' | 'none';

export interface EmotionDirective {
  emotion: EmotionTag;
  pitchDelta: number;
  speedDelta: number;
}

export interface BreathDirective {
  positionPercent: number;
  durationMs: number;
}

export interface EnrichedMessage {
  cleanText: string;
  emotion: EmotionDirective;
  breathing: BreathDirective[];
  fillerWords: string[];
  segments: SpeechSegment[];
}

export interface SpeechSegment {
  text: string;
  emotion: EmotionTag;
  pauseAfterMs: number;
  isFiller: boolean;
}

export interface BargeInResult {
  detected: boolean;
  action: BargeInAction;
  callbackMinutes?: number;
  originalText: string;
}

export interface VoiceMessage {
  id: string;
  role: MessageRole;
  type: MessageType;
  content: string;
  enriched?: EnrichedMessage;
  audioUrl?: string;
  audioDurationMs?: number;
  createdAt: string;
}

export interface VoiceCallSession {
  id: string;
  userId: string;
  agentId: string;
  status: CallStatus;
  priority: CallPriority;
  reason: CallReason;
  context: {
    taskId?: string;
    summary: string;
    options?: string[];
  };
  messages: VoiceMessage[];
  result?: {
    transcriptSummary?: string;
    userResponse?: string;
    decision?: string;
    selectedOption?: string;
    sentiment?: string;
    actionItems?: string[];
  };
  createdAt: string;
  connectedAt?: string;
  completedAt?: string;
}

export interface CreateCallInput {
  userId: string;
  agentId: string;
  reason: CallReason;
  summary: string;
  taskId?: string;
  options?: string[];
  priority?: CallPriority;
}

export interface SendMessageInput {
  callId: string;
  content: string;
}

export interface AudioChunk {
  callId: string;
  data: string;
  sequence: number;
  isFinal: boolean;
}

export interface CallbackRequest {
  callId: string;
  delayMinutes: number;
  reason: string;
}

const EMOTION_MAP: Record<string, EmotionDirective> = {
  calm: { emotion: 'calm', pitchDelta: -0.15, speedDelta: -0.2 },
  urgent: { emotion: 'urgent', pitchDelta: 0.25, speedDelta: 0.3 },
  excited: { emotion: 'excited', pitchDelta: 0.3, speedDelta: 0.2 },
  thoughtful: { emotion: 'thoughtful', pitchDelta: -0.1, speedDelta: -0.3 },
  neutral: { emotion: 'neutral', pitchDelta: 0, speedDelta: 0 },
};

const FILLER_WORDS = ['um', 'uh', 'hmm', 'well', 'so', 'actually', 'basically', 'you see', 'I mean', 'let me think'];

export function emotionOf(str: string): EmotionDirective {
  for (const [tag, dir] of Object.entries(EMOTION_MAP)) {
    const regex = new RegExp(`\\[${tag}\\]`, 'i');
    if (regex.test(str)) return dir;
  }
  return EMOTION_MAP.neutral ?? { emotion: 'neutral', pitchDelta: 0, speedDelta: 0 };
}

export function extractEmotionTag(raw: string): { clean: string; emotion: EmotionDirective } {
  let text = raw;
  let emotion: EmotionDirective = EMOTION_MAP.neutral!;
  for (const tag of Object.keys(EMOTION_MAP)) {
    const regex = new RegExp(`\\[${tag}\\]`, 'gi');
    if (regex.test(text)) {
      emotion = EMOTION_MAP[tag] ?? EMOTION_MAP.neutral!;
      text = text.replace(regex, '').trim();
    }
  }
  return { clean: text, emotion };
}

export function enrichText(raw: string): EnrichedMessage {
  const { clean, emotion } = extractEmotionTag(raw);

  const sentences = clean.split(/(?<=[.!?])\s+/).filter(Boolean);
  const segments: SpeechSegment[] = [];
  const breathing: BreathDirective[] = [];
  const fillerWords: string[] = [];

  let charCount = 0;
  const totalChars = clean.length;

  sentences.forEach((sentence, i) => {
    const isLast = i === sentences.length - 1;

    const fillerChance = emotion.emotion === 'thoughtful' ? 0.6
      : emotion.emotion === 'calm' ? 0.3
      : emotion.emotion === 'urgent' ? 0.05
      : 0.15;

    if (!isLast && Math.random() < fillerChance) {
      const filler = FILLER_WORDS[Math.floor(Math.random() * FILLER_WORDS.length)]!;
      fillerWords.push(filler);
      segments.push({ text: filler, emotion: emotion.emotion, pauseAfterMs: 200, isFiller: true });
    }

    const sentencePause = isLast ? 600
      : emotion.emotion === 'urgent' ? 200
      : emotion.emotion === 'thoughtful' ? 800
      : 400;

    segments.push({ text: sentence, emotion: emotion.emotion, pauseAfterMs: sentencePause, isFiller: false });

    const beforeCharCount = charCount;
    charCount += sentence.length;
    if (!isLast && Math.random() < 0.5) {
      const posPercent = (beforeCharCount + sentence.length / 2) / totalChars;
      breathing.push({
        positionPercent: Math.round(posPercent * 100) / 100,
        durationMs: emotion.emotion === 'urgent' ? 300 : emotion.emotion === 'thoughtful' ? 800 : 500,
      });
    }
  });

  return { cleanText: clean, emotion, breathing, fillerWords, segments };
}

export function detectBargeIn(userText: string): BargeInResult {
  const lower = userText.toLowerCase().trim();

  const callbackMatch = lower.match(/call(?: me)? back (?:in|after)\s*(\d+)?\s*(?:minutes?|min|m)?/);
  if (lower.includes('call me back') || callbackMatch) {
    const minutes = callbackMatch?.[1] ? parseInt(callbackMatch[1], 10) : 10;
    return { detected: true, action: 'callback', callbackMinutes: Math.max(1, Math.min(60, minutes)), originalText: userText };
  }

  if (lower.includes('later') || lower.includes('some time') || lower.includes('not now') || lower.includes('busy')) {
    return { detected: true, action: 'callback', callbackMinutes: 15, originalText: userText };
  }

  if (lower.includes('wait') || lower.includes('hold on') || lower.includes('stop') ||
      lower.includes('timeout') || lower.includes('pause') || lower === 'no' || lower.startsWith('wait')) {
    return { detected: true, action: 'stop', originalText: userText };
  }

  if (lower.includes('what') || lower.includes('rephrase') || lower.includes('again') ||
      lower.includes('repeat') || lower.includes('explain') || lower.includes('clarify')) {
    return { detected: true, action: 'rephrase', originalText: userText };
  }

  if (lower.includes('think') || lower.includes('let me') || lower.includes('give me a sec') ||
      lower.includes('one moment') || lower.includes('just a') || lower.includes('hang on')) {
    return { detected: true, action: 'wait', originalText: userText };
  }

  return { detected: false, action: 'none', originalText: userText };
}
