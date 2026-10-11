import { createHash, createHmac, randomBytes, scrypt as scryptCb, timingSafeEqual } from 'node:crypto';

/** 32 random bytes encoded as base64url without padding: 43 characters. */
export function newOpaqueToken(): string {
  return randomBytes(32).toString('base64url');
}

export const OPAQUE_TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;

export function sha256(data: string | Buffer): Buffer {
  return createHash('sha256').update(data).digest();
}

export function sha256Hex(data: string | Buffer): string {
  return createHash('sha256').update(data).digest('hex');
}

/** Keyed hash of a client IP, so rate limits work without storing addresses. */
export function hashIp(ip: string, salt: string): string {
  return createHmac('sha256', salt).update(ip).digest('base64url');
}

// Crockford base32 alphabet: no I, L, O or U.
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** 80 random bits as 16 Crockford base32 characters, grouped as XXXX-XXXX-XXXX-XXXX. */
export function newRecoveryCode(): string {
  const bytes = randomBytes(10);
  let bits = 0n;
  for (const b of bytes) bits = (bits << 8n) | BigInt(b);
  let out = '';
  for (let i = 15; i >= 0; i--) {
    out += CROCKFORD[Number((bits >> BigInt(i * 5)) & 31n)];
  }
  return `${out.slice(0, 4)}-${out.slice(4, 8)}-${out.slice(8, 12)}-${out.slice(12, 16)}`;
}

/**
 * Normalises user input: case-insensitive, hyphens and spaces optional, and the Crockford
 * look-alike substitutions (I and L read as 1, O reads as 0). Returns null when the input cannot
 * be a code, so no hashing work is spent on garbage.
 */
export function normalizeRecoveryCode(input: string): string | null {
  const s = input.toUpperCase().replace(/[\s-]/g, '').replace(/[IL]/g, '1').replace(/O/g, '0');
  if (s.length !== 16) return null;
  for (const ch of s) if (!CROCKFORD.includes(ch)) return null;
  return s;
}

function scrypt(password: string, salt: string, n: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scryptCb(password, salt, 32, { N: n, r: 8, p: 1, maxmem: 256 * n * 8 + 1024 * 1024 }, (err, key) =>
      err ? reject(err) : resolve(key),
    );
  });
}

/** scrypt of the normalised code. The salt is fixed per deployment (pepper) so restore can look it up. */
export function hashRecoveryCode(normalized: string, pepper: string, n: number): Promise<Buffer> {
  return scrypt(normalized, `photoduel.recovery.v1:${pepper}`, n);
}

export function safeEqual(a: Buffer, b: Buffer): boolean {
  return a.length === b.length && timingSafeEqual(a, b);
}
