import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import sharp from 'sharp';
import { ConfigError, loadConfig } from '../../src/config.js';
import { hashRecoveryCode, newOpaqueToken, newRecoveryCode, normalizeRecoveryCode, OPAQUE_TOKEN_RE } from '../../src/lib/crypto.js';
import { ApiError } from '../../src/lib/errors.js';
import { detectImageType, normalizePhoto } from '../../src/lib/images.js';
import { redactPath } from '../../src/lib/logger.js';
import { loadPromptCatalog, PromptCatalogError } from '../../src/lib/prompts.js';
import { GPS_MARKER, hasApp1, jpeg, png, webp, withGpsExif } from '../support/harness.js';

const limits = { maxInputPixels: 40_000_000, minShortEdgePx: 200 };

test('opaque tokens are 32 random bytes in base64url (43 chars) and unique', () => {
  const seen = new Set<string>();
  for (let i = 0; i < 200; i++) {
    const t = newOpaqueToken();
    assert.match(t, OPAQUE_TOKEN_RE);
    assert.equal(Buffer.from(t, 'base64url').length, 32);
    seen.add(t);
  }
  assert.equal(seen.size, 200);
});

test('recovery codes are 80-bit Crockford base32 and normalise case, hyphens and look-alikes', () => {
  const code = newRecoveryCode();
  assert.match(code, /^[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}$/);
  const raw = code.replace(/-/g, '');
  assert.equal(normalizeRecoveryCode(code.toLowerCase()), raw);
  assert.equal(normalizeRecoveryCode(raw), raw);
  assert.equal(normalizeRecoveryCode('0000-1111-2222-333O'), '0000111122223330');
  assert.equal(normalizeRecoveryCode('ilil-0000-0000-0000'), '1111000000000000');
  assert.equal(normalizeRecoveryCode('UUUU-0000-0000-0000'), null);
  assert.equal(normalizeRecoveryCode('short'), null);
});

test('recovery code hashing is scrypt, deterministic per pepper and differs across peppers', async () => {
  const a = await hashRecoveryCode('0000111122223333', 'pepper-one', 1024);
  const b = await hashRecoveryCode('0000111122223333', 'pepper-one', 1024);
  const c = await hashRecoveryCode('0000111122223333', 'pepper-two', 1024);
  assert.equal(a.length, 32);
  assert.ok(a.equals(b));
  assert.ok(!a.equals(c));
});

test('image types are detected by magic bytes only', async () => {
  assert.equal(detectImageType(await jpeg(10, 10)), 'jpeg');
  assert.equal(detectImageType(await png(10, 10)), 'png');
  assert.equal(detectImageType(await webp(10, 10)), 'webp');
  assert.equal(detectImageType(Buffer.from('GIF89a........')), null);
  assert.equal(detectImageType(Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>')), null);
  assert.equal(detectImageType(Buffer.alloc(0)), null);
});

test('normalisation strips EXIF and GPS, honours orientation and bounds size', async () => {
  const src = withGpsExif(await jpeg(2400, 1800));
  assert.ok(hasApp1(src), 'fixture must carry an APP1 segment');
  const meta = await sharp(src).metadata();
  assert.ok(meta.exif && meta.exif.includes(Buffer.from(GPS_MARKER)), 'fixture EXIF must contain the marker');

  const out = await normalizePhoto(src, await jpeg(480, 360), limits);
  assert.equal(out.full.width, 1600);
  assert.equal(out.full.height, 1200);
  assert.equal(Math.max(out.thumbnail.width, out.thumbnail.height), 480);
  for (const v of [out.full.bytes, out.thumbnail.bytes]) {
    assert.equal(hasApp1(v), false);
    assert.equal(v.includes(Buffer.from(GPS_MARKER)), false);
    const m = await sharp(v).metadata();
    assert.equal(m.exif, undefined);
    assert.equal(m.format, 'jpeg');
  }

  const rotated = await sharp(await jpeg(400, 300)).withMetadata({ orientation: 6 }).jpeg().toBuffer();
  const r = await normalizePhoto(rotated, await jpeg(400, 300), limits);
  assert.deepEqual([r.full.width, r.full.height], [300, 400]);
});

test('normalisation rejects tiny, huge and non-image input with UNSUPPORTED_MEDIA', async () => {
  const thumb = await jpeg(300, 300);
  const cases: [string, Buffer][] = [
    ['short edge under 200', await jpeg(400, 150)],
    ['over 40 MP', await png(8000, 5001)],
    ['gif', Buffer.from('GIF89a\x01\x00\x01\x00\x00\x00\x00;')],
    ['text with jpeg name', Buffer.from('hello world')],
    ['truncated jpeg', (await jpeg(400, 400)).subarray(0, 40)],
  ];
  for (const [name, buf] of cases) {
    await assert.rejects(normalizePhoto(buf, thumb, limits), (e: unknown) => {
      assert.ok(e instanceof ApiError, name);
      assert.equal(e.code, 'UNSUPPORTED_MEDIA', name);
      assert.equal(e.status, 415, name);
      return true;
    });
  }
});

test('request paths are redacted before logging', () => {
  const t = newOpaqueToken();
  assert.equal(redactPath(`/v1/invitations/${t}/join?x=1`), '/v1/invitations/[redacted]/join');
  assert.equal(redactPath(`/v1/invitations/${t}`), '/v1/invitations/[redacted]');
  assert.equal(redactPath('/v1/rooms/abc'), '/v1/rooms/abc');
});

test('configuration is validated and production requires real secrets', () => {
  const c = loadConfig({});
  assert.equal(c.port, 8787);
  assert.equal(c.limits.uploadBodyLimitBytes, 3 * 1024 * 1024);
  assert.equal(c.limits.ballotsPerUserPerMinute, 60);
  assert.equal(c.sessionTtlMs, 90 * 24 * 3600 * 1000);
  assert.throws(() => loadConfig({ PORT: 'eighty' }), ConfigError);
  assert.throws(() => loadConfig({ DATABASE_SCHEMA: 'Robert"; DROP' }), ConfigError);
  assert.throws(() => loadConfig({ NODE_ENV: 'production' }), /IP_HASH_SALT/);
  assert.throws(
    () => loadConfig({ NODE_ENV: 'production', IP_HASH_SALT: 'x'.repeat(32) }),
    /RECOVERY_CODE_PEPPER/,
  );
  assert.doesNotThrow(() =>
    loadConfig({ NODE_ENV: 'production', IP_HASH_SALT: 'x'.repeat(32), RECOVERY_CODE_PEPPER: 'y'.repeat(32) }),
  );
});

test('prompt catalogue loads from the shared asset file', () => {
  const c = loadPromptCatalog(loadConfig({}).promptsPath);
  assert.ok(c.version.length > 0);
  assert.equal(c.prompts.length, 30);
  assert.ok(c.byId.has('p-001'));
  for (const p of c.prompts) assert.deepEqual(Object.keys(p).sort(), ['brief', 'id', 'title']);
});

test('prompt loader fails clearly on a missing or malformed catalogue (temporary fixtures)', () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'photoduel-prompts-'));
  try {
    assert.throws(() => loadPromptCatalog(path.join(dir, 'missing.json')), (e: unknown) => {
      assert.ok(e instanceof PromptCatalogError);
      assert.match(e.message, /not found or unreadable/);
      assert.match(e.message, /PROMPTS_PATH/);
      return true;
    });
    const bad = path.join(dir, 'bad.json');
    writeFileSync(bad, '{ not json');
    assert.throws(() => loadPromptCatalog(bad), /not valid JSON/);
    const dup = path.join(dir, 'dup.json');
    writeFileSync(
      dup,
      JSON.stringify({ catalogVersion: '9.9.9', prompts: [{ id: 'p-1', title: 'A', brief: 'B' }, { id: 'p-1', title: 'C', brief: 'D' }] }),
    );
    assert.throws(() => loadPromptCatalog(dup), /repeats id p-1/);
    const ok = path.join(dir, 'ok.json');
    writeFileSync(ok, JSON.stringify({ version: '2.0.0', prompts: [{ id: 'x-1', title: 'T', brief: 'B', extra: 1 }] }));
    const c = loadPromptCatalog(ok);
    assert.equal(c.version, '2.0.0');
    assert.deepEqual(c.prompts, [{ id: 'x-1', title: 'T', brief: 'B' }]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
