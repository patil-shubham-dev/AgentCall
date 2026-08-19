import { describe, it, expect, beforeEach } from 'vitest';
import {
  initializeAiKeys,
  createAiKey,
  resolveAiKey,
  listAiKeys,
  listAiKeyStatuses,
  deleteAiKey,
  renameAiKey,
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

  it('renames a key by its stable id; identity resolves to the new name', async () => {
    const created = await createAiKey('Old Name');
    expect(await renameAiKey(created.id, 'New Name')).toBe(true);
    expect((await listAiKeys()).find((k) => k.id === created.id)?.name).toBe('New Name');
    expect(await resolveAiKey(created.key)).toEqual({ id: created.id, name: 'New Name' });
    expect(await renameAiKey('missing-id', 'Anything')).toBe(false);
  });

  it('names are sanitized by the caller; registry stores what it is given', async () => {
    const created = await createAiKey('  My AI  ');
    expect(created.name).toBe('  My AI  ');
  });
});

describe('ai-key availability status (memory mode)', () => {
  beforeEach(async () => {
    await initializeAiKeys();
  });

  it('marks a recently-used key online and idle keys offline', async () => {
    const used = await createAiKey('UsedAI');
    await resolveAiKey(used.key); // sets lastUsedAt = now

    await createAiKey('IdleAI');

    const statuses = await listAiKeyStatuses(new Set());
    const usedStatus = statuses.find((s) => s.name === 'UsedAI');
    const idleStatus = statuses.find((s) => s.name === 'IdleAI');

    expect(usedStatus?.online).toBe(true);
    expect(usedStatus?.busy).toBe(false);
    expect(usedStatus?.lastSeenAt).not.toBeNull();
    expect(idleStatus?.online).toBe(false);
    expect(idleStatus?.busy).toBe(false);
  });

  it('marks a key busy when its agent name has an active call', async () => {
    const key = await createAiKey('BusyAgent');
    await resolveAiKey(key.key);

    const statuses = await listAiKeyStatuses(new Set(['BusyAgent']));
    const status = statuses.find((s) => s.name === 'BusyAgent');

    expect(status?.online).toBe(true);
    expect(status?.busy).toBe(true);
  });

  it('does not mark busy for agent names without a matching key', async () => {
    await createAiKey('QuietAI');
    const statuses = await listAiKeyStatuses(new Set(['SomeoneElse']));
    expect(statuses.find((s) => s.name === 'QuietAI')?.busy).toBe(false);
  });
});
