import { describe, it, expect } from 'vitest';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { resolveSecretFilePath, assertFcmKeyFile, RENDER_SECRETS_DIR } from '../common/config.js';

describe('resolveSecretFilePath (FCM key on Render Secret Files)', () => {
  it('returns the configured path untouched when the file exists there (local dev)', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'fcm-key-'));
    const keyFile = path.join(dir, 'firebase-service-account.json');
    writeFileSync(keyFile, '{"type":"service_account"}');
    expect(resolveSecretFilePath(keyFile)).toBe(keyFile);
  });

  it('falls back to the /etc/secrets mount when the configured path is absent', () => {
    const exists = (p: string) => p === path.join(RENDER_SECRETS_DIR, 'firebase-service-account.json');
    expect(resolveSecretFilePath('firebase-service-account.json', exists)).toBe(
      path.join(RENDER_SECRETS_DIR, 'firebase-service-account.json'),
    );
  });

  it('returns the configured path unresolved when neither location exists (callers fail fast)', () => {
    expect(resolveSecretFilePath('firebase-service-account.json', () => false)).toBe(
      'firebase-service-account.json',
    );
  });

  it('returns empty for an empty configured path (FCM disabled wiring)', () => {
    expect(resolveSecretFilePath('')).toBe('');
  });
});

describe('assertFcmKeyFile (boot fail-fast)', () => {
  it('throws the set-up error when no path is configured', () => {
    expect(() => assertFcmKeyFile('')).toThrow(/FIREBASE_SERVICE_ACCOUNT_PATH to be set/);
  });

  it('throws naming the path and the Secret Files mount when the file is unreadable', () => {
    expect(() => assertFcmKeyFile('/etc/secrets/firebase-service-account.json', () => false)).toThrow(
      /unreadable at "\/etc\/secrets\/firebase-service-account\.json".*Secret File/s,
    );
  });

  it('does not throw when the resolved file exists', () => {
    expect(() => assertFcmKeyFile('/etc/secrets/firebase-service-account.json', () => true)).not.toThrow();
  });
});
