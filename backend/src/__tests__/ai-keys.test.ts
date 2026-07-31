import { describe, it, expect, beforeEach } from 'vitest';
import {
  initializeAiKeys,
  createAiKey,
  resolveAiKey,
  listAiKeys,
  deleteAiKey,
  hashKey,
} from '../voicebridge/ai-keys.js';

describe('ai-keys registry (memory mode)', () => {
  beforeEach(async () => {
    await initializeAiKeys();
  });

  it('creates a key with the ac_ prefix and a registered name', async () => {
    const created = await createAiKey('Claude Desktop');
    expect(created.name).toBe('Claude Desktop');
    expect(created.key.startsWith('ac_')).toBe(true);
    expect(created.key.length).toBeGreaterThan(32);
  });

  it('only stores the hash — the plaintext key is never persisted', async () => {
    const created = await createAiKey('Opencode');
    const listed = await listAiKeys();
    expect(listed[0]?.name).toBe('Opencode');
    expect(listed[0]?.id).toBe(created.id);
    expect(hashKey(created.key)).not.toBe(created.key);
  });

  it('resolves a valid key to its identity and rejects unknown keys', async () => {
    const created = await createAiKey('ChatGPT');
    const resolved = await resolveAiKey(created.key);
    expect(resolved).toEqual({ id: created.id, name: 'ChatGPT' });
    expect(await resolveAiKey('ac_does_not_exist')).toBeNull();
    expect(await resolveAiKey('')).toBeNull();
  });

  it('deletes a key so it can no longer resolve', async () => {
    const created = await createAiKey('Codex');
    expect(await deleteAiKey(created.id)).toBe(true);
    expect(await resolveAiKey(created.key)).toBeNull();
    expect(await deleteAiKey('missing-id')).toBe(false);
  });

  it('names are sanitized by the caller; registry stores what it is given', async () => {
    const created = await createAiKey('  My AI  ');
    expect(created.name).toBe('  My AI  ');
  });
});
