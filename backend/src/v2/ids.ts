import { randomBytes } from 'node:crypto';

/**
 * UUID v7: 48-bit big-endian Unix-epoch millisecond timestamp + 74 bits of
 * randomness (version 7 / variant 10). Sortable by creation order and globally
 * unique — the event-log sequence tiebreaker for equal timestamps is the
 * per-call `sequence`, so this only needs to be *stable*, not collision-proof.
 */
export function uuidV7(): string {
  const bytes = randomBytes(16);
  const now = BigInt(Date.now());

  bytes[0] = Number((now >> 40n) & 0xffn);
  bytes[1] = Number((now >> 32n) & 0xffn);
  bytes[2] = Number((now >> 24n) & 0xffn);
  bytes[3] = Number((now >> 16n) & 0xffn);
  bytes[4] = Number((now >> 8n) & 0xffn);
  bytes[5] = Number(now & 0xffn);
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x70; // version 7
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80; // RFC 4122 variant

  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
