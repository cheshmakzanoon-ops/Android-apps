import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { after, before, test } from 'node:test';
import sharp from 'sharp';
import {
  createTestContext,
  GPS_MARKER,
  hasApp1,
  jpeg,
  multipart,
  photoParts,
  png,
  upload,
  webp,
  withGpsExif,
  type Player,
  type TestContext,
} from '../support/harness.js';
import {
  expectError,
  expectStatus,
  listFiles,
  mod,
  openRound,
  players,
  storedMedia,
  toCompleted,
  toVoting,
  viewChallenge,
} from '../support/scenario.js';

let ctx: TestContext;
before(async () => {
  ctx = await createTestContext();
});
after(async () => {
  await ctx?.close();
});

const getMedia = (id: string, p?: Player, headers: Record<string, string> = {}) =>
  ctx.api.call('GET', `/v1/media/${id}`, { ...(p ? { token: p.token } : {}), headers });

async function expectBytes(id: string, p: Player): Promise<Buffer> {
  const r = await getMedia(id, p);
  assert.equal(r.status, 200, `${p.name} should see ${id}: ${r.status} ${JSON.stringify(r.json)}`);
  assert.equal(r.headers['content-type'], 'image/jpeg');
  return r.raw;
}

async function expectHidden(id: string, p: Player, label: string): Promise<void> {
  const r = await getMedia(id, p);
  assert.equal(r.status, 404, `${label}: expected 404, got ${r.status}`);
  assert.equal(r.json?.error?.code, 'NOT_FOUND', label);
  assert.equal(String(r.headers['content-type']).startsWith('image/'), false, label);
}

test('media is revoked at once when the viewer leaves, is removed, blocks or is blocked', async () => {
  const [owner, author, leaver, removed, blockedByAuthor, blocker] = await players(ctx.api, 'Ada', 'Ben', 'Lou', 'Rex', 'Kim', 'Kai');
  const round = await openRound(ctx, owner!, [author!, leaver!, removed!, blockedByAuthor!, blocker!], [owner!, author!]);
  const sub = round.subs.get(author!.id);
  const ids = [sub.fullMediaId as string, sub.thumbnailMediaId as string];

  // Before voting opens, nobody but the author sees the photo.
  for (const id of ids) {
    await expectHidden(id, leaver!, 'before voting');
    await expectBytes(id, author!);
  }
  await toVoting(ctx, owner!, round);
  const etagRes = await getMedia(ids[0]!, leaver!);
  expectStatus(etagRes, 200);
  const etag = String(etagRes.headers.etag);
  assert.equal(etagRes.headers['cache-control'], 'private, max-age=300');
  assert.equal(etagRes.headers['x-content-type-options'], 'nosniff');
  assert.equal(etagRes.headers['content-disposition'], 'inline');
  for (const v of [leaver!, removed!, blockedByAuthor!, blocker!]) for (const id of ids) await expectBytes(id, v);

  // Blocks take effect on the next request, in both directions.
  expectStatus(await ctx.api.put(`/v1/blocks/${blockedByAuthor!.id}`, author!.token), 204);
  expectStatus(await ctx.api.put(`/v1/blocks/${author!.id}`, blocker!.token), 204);
  for (const id of ids) {
    await expectHidden(id, blockedByAuthor!, 'blocked by the author');
    await expectHidden(id, blocker!, 'blocked the author');
  }
  // A cached ETag does not turn a revoked viewer into a 304.
  const revalidate = await getMedia(ids[0]!, blocker!, { 'if-none-match': etag });
  expectError(revalidate, 404, 'NOT_FOUND');
  // A member who still may see it gets a 304 on revalidation.
  expectStatus(await getMedia(ids[0]!, leaver!, { 'if-none-match': etag }), 304);

  // The roster is frozen during voting; leaving is refused and access stays.
  expectError(await ctx.api.post(`/v1/rooms/${round.roomId}/leave`, leaver!.token, {}), 409, 'ROSTER_FROZEN');
  await expectBytes(ids[0]!, leaver!);

  await toCompleted(ctx, owner!, round);
  for (const id of ids) await expectBytes(id, leaver!);
  expectStatus(await ctx.api.post(`/v1/rooms/${round.roomId}/leave`, leaver!.token, {}), 204);
  expectStatus(await ctx.api.del(`/v1/rooms/${round.roomId}/members/${removed!.id}`, owner!.token), 204);
  for (const id of ids) {
    await expectHidden(id, leaver!, 'left the room');
    await expectHidden(id, removed!, 'removed from the room');
    await expectHidden(id, blockedByAuthor!, 'still blocked');
    await expectHidden(id, blocker!, 'still blocking');
  }
  // Unblocking restores access for a member who is still in the room.
  expectStatus(await ctx.api.del(`/v1/blocks/${author!.id}`, blocker!.token), 204);
  await expectBytes(ids[0]!, blocker!);
  // The owner was never affected.
  await expectBytes(ids[0]!, owner!);
});

test('media is refused once the viewer is suspended or deletes the account (401: the session is dead)', async () => {
  const [owner, author, suspended, deleter] = await players(ctx.api, 'Ada', 'Ben', 'Sam', 'Xia');
  const round = await openRound(ctx, owner!, [author!, suspended!, deleter!], [owner!, author!]);
  await toVoting(ctx, owner!, round);
  const id = round.subs.get(author!.id).fullMediaId as string;
  await expectBytes(id, suspended!);
  await expectBytes(id, deleter!);

  const s = await mod(ctx, 'suspend-user', suspended!.id, '--reason', 'abuse', '--operator', 'mod-test');
  assert.equal(s.code, 0, s.err.join('\n'));
  // api-contract.md section 10: a suspended account gets 401 UNAUTHENTICATED on every request.
  const r1 = await getMedia(id, suspended!);
  expectError(r1, 401, 'UNAUTHENTICATED');
  assert.equal(r1.raw.includes(Buffer.from([0xff, 0xd8, 0xff])), false);
  // Restoring the account restores access, so the 401 above came from the suspension.
  assert.equal((await mod(ctx, 'restore-user', suspended!.id, '--operator', 'mod-test')).code, 0);
  await expectBytes(id, suspended!);

  expectStatus(await ctx.api.del('/v1/me', deleter!.token, { confirm: 'DELETE' }), 204);
  const r2 = await getMedia(id, deleter!);
  expectError(r2, 401, 'UNAUTHENTICATED');
  assert.equal(r2.raw.includes(Buffer.from([0xff, 0xd8, 0xff])), false);
});

test('media returns 404 once its author deletes the account or the photo, and the bytes are gone', async () => {
  const [owner, author, viewer, other] = await players(ctx.api, 'Ada', 'Ben', 'Vic', 'Ola');
  const round = await openRound(ctx, owner!, [author!, viewer!, other!], [owner!, author!, other!]);
  await toCompleted(ctx, owner!, round);
  const bSub = round.subs.get(author!.id);
  const oSub = round.subs.get(other!.id);
  await expectBytes(bSub.fullMediaId, viewer!);
  await expectBytes(oSub.thumbnailMediaId, viewer!);

  // Author deletes the photo after completion: media purged, row REMOVED.
  expectStatus(await ctx.api.del(`/v1/submissions/${oSub.id}`, other!.token), 204);
  for (const id of [oSub.fullMediaId, oSub.thumbnailMediaId]) {
    await expectHidden(id, viewer!, 'deleted photo');
    await expectHidden(id, other!, 'deleted photo, author');
    const m = await storedMedia(ctx, id);
    assert.equal(m.purged, true);
    assert.equal(m.bytes, null, 'object removed from the store');
  }

  // Author deletes the account.
  expectStatus(await ctx.api.del('/v1/me', author!.token, { confirm: 'DELETE' }), 204);
  for (const id of [bSub.fullMediaId, bSub.thumbnailMediaId]) {
    await expectHidden(id, viewer!, 'author deleted account');
    await expectHidden(id, owner!, 'author deleted account, owner');
    const m = await storedMedia(ctx, id);
    assert.equal(m.purged, true);
    assert.equal(m.bytes, null);
  }
});

test('uploads with the wrong magic bytes return 415 whatever the file name and Content-Type claim', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], []);
  const good = await jpeg(800, 600);
  const thumb = await jpeg(480, 360);
  const filesBefore = listFiles(ctx.mediaRoot);
  const bad: [string, Buffer][] = [
    ['gif', Buffer.concat([Buffer.from('GIF89a', 'latin1'), Buffer.alloc(64, 1)])],
    ['pdf', Buffer.from('%PDF-1.4\n1 0 obj\n<<>>\nendobj\n', 'latin1')],
    ['svg', Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>')],
    ['html', Buffer.from('<!doctype html><script>alert(1)</script>')],
    ['jpeg magic then garbage', Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(4096, 7)])],
    ['png magic then garbage', Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(4096, 7)])],
    ['png bytes after a jpeg header byte', Buffer.concat([Buffer.from([0xff]), await png(400, 400)])],
    ['empty', Buffer.alloc(0)],
  ];
  for (const [name, data] of bad) {
    const asFull = await upload(ctx.api, b!, round.challengeId, [
      { name: 'full', data, filename: 'photo.jpg', contentType: 'image/jpeg' },
      { name: 'thumbnail', data: thumb, filename: 'thumb.jpg', contentType: 'image/jpeg' },
    ]);
    expectError(asFull, 415, 'UNSUPPORTED_MEDIA');
    const asThumb = await upload(ctx.api, b!, round.challengeId, [
      { name: 'full', data: good, filename: 'photo.jpg', contentType: 'image/jpeg' },
      { name: 'thumbnail', data, filename: 'thumb.jpg', contentType: 'image/jpeg' },
    ]);
    expectError(asThumb, 415, 'UNSUPPORTED_MEDIA');
    void name;
  }
  assert.deepEqual(listFiles(ctx.mediaRoot), filesBefore, 'nothing stored for rejected uploads');
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM submissions WHERE challenge_id = $1', [round.challengeId])).rows[0].n, 0);

  // Real PNG and WebP inputs are accepted (and stored as JPEG) even when labelled as something else.
  const fromPng = await upload(ctx.api, b!, round.challengeId, [
    { name: 'full', data: await png(800, 600), filename: 'x.gif', contentType: 'image/gif' },
    { name: 'thumbnail', data: await webp(480, 360), filename: 'y.txt', contentType: 'text/plain' },
  ]);
  expectStatus(fromPng, 201);
  const stored = await storedMedia(ctx, fromPng.json.fullMediaId);
  assert.equal((await sharp(stored.bytes!).metadata()).format, 'jpeg');
});

test('an oversize upload body returns 413, by declared length and while streaming', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], []);
  const limit = ctx.config.limits.uploadBodyLimitBytes;
  assert.equal(limit, 3 * 1024 * 1024);
  const filesBefore = listFiles(ctx.mediaRoot);

  const head = await jpeg(800, 600);
  const big = Buffer.concat([head, Buffer.alloc(limit + 1 - head.length, 0)]);
  const r = await upload(ctx.api, b!, round.challengeId, photoParts(big, await jpeg(480, 360)));
  expectError(r, 413, 'PAYLOAD_TOO_LARGE');

  // JSON bodies are capped at 16 KB.
  const json = await ctx.api.post('/v1/rooms', a!.token, { name: 'x'.repeat(17 * 1024) });
  expectError(json, 413, 'PAYLOAD_TOO_LARGE');

  // Chunked transfer without a Content-Length: the limit is enforced while reading the stream.
  await ctx.app.listen({ host: '127.0.0.1', port: 0 });
  const port = (ctx.app.server.address() as AddressInfo).port;
  const mp = multipart([
    { name: 'full', data: Buffer.concat([head, Buffer.alloc(limit, 0)]), filename: 'full.jpg', contentType: 'image/jpeg' },
    { name: 'thumbnail', data: await jpeg(480, 360), filename: 'thumb.jpg', contentType: 'image/jpeg' },
  ]);
  const streamed = await new Promise<{ status: number; body: string }>((resolve, reject) => {
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        method: 'PUT',
        path: `/v1/challenges/${round.challengeId}/submission`,
        headers: { ...mp.headers, authorization: `Bearer ${b!.token}`, 'transfer-encoding': 'chunked' },
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        res.on('end', () => {
          resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks).toString('utf8') });
          req.destroy();
        });
      },
    );
    let settled = false;
    req.on('response', () => {
      settled = true;
    });
    req.on('error', (e) => {
      if (!settled) reject(e);
    });
    let offset = 0;
    const step = 64 * 1024;
    const pump = () => {
      while (offset < mp.payload.length && !settled) {
        const chunk = mp.payload.subarray(offset, offset + step);
        offset += step;
        if (!req.write(chunk)) {
          req.once('drain', pump);
          return;
        }
      }
      if (!settled) req.end();
    };
    pump();
  });
  assert.equal(streamed.status, 413, streamed.body);
  assert.equal(JSON.parse(streamed.body).error.code, 'PAYLOAD_TOO_LARGE');

  assert.deepEqual(listFiles(ctx.mediaRoot), filesBefore);
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM submissions WHERE challenge_id = $1', [round.challengeId])).rows[0].n, 0);
});

test('a photo with a GPS EXIF block is stored with no GPS data (stored bytes inspected)', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], []);
  const full = withGpsExif(await jpeg(2400, 1800));
  const thumb = withGpsExif(await jpeg(480, 360));
  // The fixture really carries GPS data.
  const srcMeta = await sharp(full).metadata();
  assert.ok(srcMeta.exif && srcMeta.exif.includes(Buffer.from(GPS_MARKER)));
  assert.ok(hasApp1(full) && hasApp1(thumb));

  const r = await upload(ctx.api, b!, round.challengeId, photoParts(full, thumb));
  expectStatus(r, 201);
  assert.equal(r.json.width, 1600);
  assert.equal(r.json.height, 1200);

  for (const [id, longEdge] of [[r.json.fullMediaId, 1600], [r.json.thumbnailMediaId, 480]] as const) {
    const m = await storedMedia(ctx, id);
    assert.ok(m.bytes, 'object present on disk');
    const bytes = m.bytes!;
    assert.equal(hasApp1(bytes), false, 'no APP1 (EXIF/XMP) segment');
    assert.equal(bytes.includes(Buffer.from(GPS_MARKER)), false, 'no EXIF text survives');
    assert.equal(bytes.includes(Buffer.from('Exif\0\0', 'latin1')), false, 'no EXIF header anywhere');
    const meta = await sharp(bytes).metadata();
    assert.equal(meta.format, 'jpeg');
    assert.equal(meta.exif, undefined);
    assert.equal(meta.xmp, undefined);
    assert.equal(meta.iptc, undefined);
    assert.equal(Math.max(meta.width!, meta.height!), longEdge);
    // The recorded hash is the hash of the stored bytes, and the API serves exactly those bytes.
    assert.equal(createHash('sha256').update(bytes).digest('hex'), m.sha256);
    const served = await getMedia(id, b!);
    expectStatus(served, 200);
    assert.ok(served.raw.equals(bytes));
    assert.equal(served.headers.etag, `"${m.sha256}"`);
  }
});

test('the author cannot delete or withdraw a photo during VOTING', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!, c!]);
  const sub = round.subs.get(b!.id);
  // While submissions are open, the final delete route is refused (withdraw is the open-window path).
  expectError(await ctx.api.del(`/v1/submissions/${sub.id}`, b!.token), 409, 'INVALID_TRANSITION');
  await toVoting(ctx, a!, round);
  expectError(await ctx.api.del(`/v1/submissions/${sub.id}`, b!.token), 409, 'INVALID_TRANSITION');
  expectError(await ctx.api.del(`/v1/challenges/${round.challengeId}/submission`, b!.token), 409, 'INVALID_TRANSITION');
  // Another member cannot delete it either, and learns nothing.
  expectError(await ctx.api.del(`/v1/submissions/${sub.id}`, c!.token), 404, 'NOT_FOUND');

  const row = await ctx.pool.query('SELECT status FROM submissions WHERE id = $1', [sub.id]);
  assert.equal(row.rows[0].status, 'ACTIVE');
  for (const id of [sub.fullMediaId, sub.thumbnailMediaId]) {
    const m = await storedMedia(ctx, id);
    assert.equal(m.purged, false);
    assert.ok(m.bytes);
    await expectBytes(id, c!);
  }
  const view = await viewChallenge(ctx.api, c!, round.challengeId);
  assert.ok(view.submissions.some((s: any) => s.id === sub.id));

  // After completion the author may delete it; the tally is kept.
  const done = await toCompleted(ctx, a!, round);
  expectStatus(await ctx.api.del(`/v1/submissions/${sub.id}`, b!.token), 204);
  const later = await viewChallenge(ctx.api, a!, round.challengeId);
  assert.deepEqual(later.result, done.result);
});
