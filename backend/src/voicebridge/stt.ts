import { logger } from '../common/logger.js';

type Transcoder = (audio: Float32Array) => Promise<string>;

let transcode: Transcoder | null = null;
let modelLoading = false;
let modelLoaded = false;

function loadTranscoder(): void {
  modelLoading = true;
  import('@xenova/transformers')
    .then(async (mod) => {
      const { pipeline } = mod;
      logger.info('Loading Whisper STT model (Xenova/whisper-base)...');
      const start = Date.now();
      const p = await pipeline('automatic-speech-recognition', 'Xenova/whisper-base');
      logger.info({ durationMs: Date.now() - start }, 'Whisper model loaded');
      transcode = async (audio: Float32Array) => {
        const result = await p(audio, { language: 'english', task: 'transcribe' });
        return (result as { text: string }).text;
      };
      modelLoaded = true;
      modelLoading = false;
    })
    .catch((err) => {
      logger.error({ err }, 'Failed to load Whisper model');
      modelLoading = false;
    });
}

loadTranscoder();

function float32ToPCM16(buffer: Float32Array): Int16Array {
  const pcm = new Int16Array(buffer.length);
  for (let i = 0; i < buffer.length; i++) {
    const s = Math.max(-1, Math.min(1, buffer[i]!));
    pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
  }
  return pcm;
}

function pcm16ToWav(pcm: Int16Array, sampleRate: number): ArrayBuffer {
  const numChannels = 1;
  const bitsPerSample = 16;
  const byteRate = sampleRate * numChannels * (bitsPerSample / 8);
  const blockAlign = numChannels * (bitsPerSample / 8);
  const dataSize = pcm.length * (bitsPerSample / 8);
  const headerSize = 44;
  const buf = new ArrayBuffer(headerSize + dataSize);
  const view = new DataView(buf);

  const writeString = (offset: number, str: string) => {
    for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
  };

  writeString(0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  writeString(8, 'WAVE');
  writeString(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, numChannels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, byteRate, true);
  view.setUint16(32, blockAlign, true);
  view.setUint16(34, bitsPerSample, true);
  writeString(36, 'data');
  view.setUint32(40, dataSize, true);

  for (let i = 0; i < pcm.length; i++) {
    view.setInt16(headerSize + i * 2, pcm[i]!, true);
  }

  return buf;
}

function decodeWavToFloat32(wavData: ArrayBuffer): Float32Array {
  const view = new DataView(wavData);
  const numChannels = view.getUint16(22, true);
  const sampleRate = view.getUint32(24, true);
  const bitsPerSample = view.getUint16(34, true);
  const dataSize = view.getUint32(40, true);
  const numSamples = Math.floor(dataSize / (bitsPerSample / 8) / numChannels);

  const result = new Float32Array(numSamples);
  let offset = 44;

  if (bitsPerSample === 16) {
    for (let i = 0; i < numSamples; i++) {
      const sample = view.getInt16(offset + i * 2 * numChannels, true);
      result[i] = sample / 32768;
      if (numChannels > 1) offset += (numChannels - 1) * 2;
    }
  } else if (bitsPerSample === 32) {
    for (let i = 0; i < numSamples; i++) {
      result[i] = view.getFloat32(offset + i * 4 * numChannels, true);
      if (numChannels > 1) offset += (numChannels - 1) * 4;
    }
  } else if (bitsPerSample === 8) {
    for (let i = 0; i < numSamples; i++) {
      result[i] = (view.getUint8(offset + i * numChannels) - 128) / 128;
    }
  }

  if (sampleRate !== 16000) {
    const ratio = 16000 / sampleRate;
    const resampled = new Float32Array(Math.floor(numSamples * ratio));
    for (let i = 0; i < resampled.length; i++) {
      const srcIdx = Math.floor(i / ratio);
      resampled[i] = result[Math.min(srcIdx, numSamples - 1)]!;
    }
    return resampled;
  }

  return result;
}

export async function transcribeAudio(wavBuffer: ArrayBuffer): Promise<string> {
  if (!modelLoaded && !modelLoading) {
    loadTranscoder();
  }

  if (!modelLoaded) {
    const waitStart = Date.now();
    while (!modelLoaded) {
      if (Date.now() - waitStart > 120_000) {
        throw new Error('STT model load timeout (120s). Check network for model download.');
      }
      await new Promise((r) => setTimeout(r, 500));
    }
  }

  if (!transcode) throw new Error('STT transcoder not available');

  const float32Audio = decodeWavToFloat32(wavBuffer);
  const text = await transcode(float32Audio);
  return text;
}
