import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { createTestContext, guest, HOUR, roomWith, startedChallenge, submitPhoto, type Res, type TestContext } from '../support/harness.js';
import { expectError, expectStatus, openRound, players, toVoting, vote } from '../support/scenario.js';

let ctx: TestContext;
before(async () => {
  // Small limits so each quota can be exhausted quickly. Production defaults are covered by the
  // configuration unit test.
  ctx = await createTestContext({
    GUESTS_PER_IP_PER_HOUR: '3',
    RESTORE_FAILURES_PER_IP_PER_HOUR: '2',
    ROOMS_PER_USER_PER_DAY: '2',
    BALLOTS_PER_USER_PER_MINUTE: '3',
    REPORTS_PER_USER_PER_DAY: '2',
    UPLOADS_PER_USER_PER_DAY: '2',
    RECOVERY_SCRYPT_N: '1024',
  });
});
after(async () => {
  await ctx?.close();
});

/** 429 RATE_LIMITED with retryAfterSeconds in the body and a matching Retry-After header. */
function expectRateLimited(r: Res, windowSeconds: number): number {
  expectError(r, 429, 'RATE_LIMITED');
  const s = r.json.error.retryAfterSeconds;
  assert.ok(Number.isInteger(s), `retryAfterSeconds must be an integer: ${JSON.stringify(r.json)}`);
  assert.ok(s >= 1 && s <= windowSeconds, `retryAfterSeconds ${s} outside 1..${windowSeconds}`);
  assert.equal(r.headers['retry-after'], String(s));
  return s;
}

const alignTo = (windowMs: number) => {
  // Start each quota check at the beginning of a fresh fixed window, so the expected retry time is exact.
  ctx.clock.set((Math.floor(ctx.clock.now() / windowMs) + 1) * windowMs);
};

test('guest creation is limited per client address', async () => {
  alignTo(HOUR);
  const ip = '10.250.0.1';
  for (let i = 0; i < 3; i++) {
    expectStatus(await ctx.api.call('POST', '/v1/identity/guest', { body: { displayName: `G${i}` }, ip }), 201);
  }
  ctx.clock.advance(10 * 60_000);
  const limited = await ctx.api.call('POST', '/v1/identity/guest', { body: { displayName: 'G3' }, ip });
  assert.equal(expectRateLimited(limited, 3600), 50 * 60);
  // Another address is unaffected; the same address recovers in the next window.
  expectStatus(await ctx.api.call('POST', '/v1/identity/guest', { body: { displayName: 'Other' }, ip: '10.250.0.2' }), 201);
  ctx.clock.advance(50 * 60_000);
  expectStatus(await ctx.api.call('POST', '/v1/identity/guest', { body: { displayName: 'G4' }, ip }), 201);
});

test('failed recovery attempts are limited per address, even for a valid code afterwards', async () => {
  alignTo(HOUR);
  const user = await guest(ctx.api, 'Restorer');
  const issued = await ctx.api.post('/v1/identity/recovery-code', user.token);
  expectStatus(issued, 201);
  const code = issued.json.recoveryCode as string;
  const ip = '10.251.0.1';
  for (let i = 0; i < 2; i++) {
    const bad = await ctx.api.call('POST', '/v1/identity/restore', { body: { recoveryCode: '0000-0000-0000-0000' }, ip });
    expectError(bad, 401, 'UNAUTHENTICATED');
  }
  const limited = await ctx.api.call('POST', '/v1/identity/restore', { body: { recoveryCode: code }, ip });
  expectRateLimited(limited, 3600);
  // The code was not consumed by the refused attempt: it still works from another address.
  const ok = await ctx.api.call('POST', '/v1/identity/restore', { body: { recoveryCode: code }, ip: '10.251.0.2' });
  expectStatus(ok, 200);
  assert.equal(ok.json.user.id, user.id);
});

test('room creation is limited per user per day', async () => {
  alignTo(24 * HOUR);
  const [u] = await players(ctx.api, 'Roomer');
  expectStatus(await ctx.api.post('/v1/rooms', u!.token, { name: 'One' }), 201);
  expectStatus(await ctx.api.post('/v1/rooms', u!.token, { name: 'Two' }), 201);
  ctx.clock.advance(HOUR);
  const limited = await ctx.api.post('/v1/rooms', u!.token, { name: 'Three' });
  assert.equal(expectRateLimited(limited, 24 * 3600), 23 * 3600);
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM rooms WHERE owner_id = $1', [u!.id])).rows[0].n, 2);
  const [other] = await players(ctx.api, 'Other');
  expectStatus(await ctx.api.post('/v1/rooms', other!.token, { name: 'Theirs' }), 201);
  ctx.clock.advance(23 * HOUR);
  expectStatus(await ctx.api.post('/v1/rooms', u!.token, { name: 'Three' }), 201);
});

test('ballot requests are limited per user per minute', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!, c!]);
  await toVoting(ctx, a!, round);
  alignTo(60_000);
  const target = round.subs.get(b!.id).id;
  expectStatus(await vote(ctx.api, a!, round.challengeId, target), 201);
  expectStatus(await vote(ctx.api, a!, round.challengeId, target), 200);
  expectStatus(await vote(ctx.api, a!, round.challengeId, target), 200);
  ctx.clock.advance(15_000);
  assert.equal(expectRateLimited(await vote(ctx.api, a!, round.challengeId, target), 60), 45);
  // Other voters have their own budget.
  expectStatus(await vote(ctx.api, c!, round.challengeId, target), 201);
  ctx.clock.advance(45_000);
  expectStatus(await vote(ctx.api, a!, round.challengeId, target), 200);
});

test('uploads are limited per user per day', async () => {
  alignTo(24 * HOUR);
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  // A 72-hour submission window, so the challenge is still open in the next quota window.
  const roomId = await roomWith(ctx.api, a!, [b!, c!]);
  const challengeId = await startedChallenge(ctx.api, a!, roomId, 72, 1);

  expectStatus(await submitPhoto(ctx.api, b!, challengeId, '#101010'), 201);
  expectStatus(await submitPhoto(ctx.api, b!, challengeId, '#202020'), 200);
  const limited = await submitPhoto(ctx.api, b!, challengeId, '#303030');
  expectRateLimited(limited, 24 * 3600);
  expectStatus(await submitPhoto(ctx.api, c!, challengeId, '#404040'), 201);
  ctx.clock.advance(24 * HOUR);
  expectStatus(await submitPhoto(ctx.api, b!, challengeId, '#505050'), 200);
});

test('reports are limited per user per day; a repeat of an open report does not count', async () => {
  alignTo(24 * HOUR);
  const [r, x, y, z] = await players(ctx.api, 'Rep', 'Xan', 'Yul', 'Zed');
  await roomWith(ctx.api, r!, [x!, y!, z!]);
  expectStatus(await ctx.api.post('/v1/reports', r!.token, { targetType: 'USER', targetId: x!.id, reason: 'SPAM' }), 201);
  expectStatus(await ctx.api.post('/v1/reports', r!.token, { targetType: 'USER', targetId: x!.id, reason: 'SPAM' }), 200);
  expectStatus(await ctx.api.post('/v1/reports', r!.token, { targetType: 'USER', targetId: y!.id, reason: 'SPAM' }), 201);
  const limited = await ctx.api.post('/v1/reports', r!.token, { targetType: 'USER', targetId: z!.id, reason: 'SPAM' });
  expectRateLimited(limited, 24 * 3600);
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM reports WHERE reporter_id = $1', [r!.id])).rows[0].n, 2);
});

test('concurrent requests cannot slip past a quota', async () => {
  alignTo(24 * HOUR);
  const [u] = await players(ctx.api, 'Racer');
  const results = await Promise.all(Array.from({ length: 10 }, (_, i) => ctx.api.post('/v1/rooms', u!.token, { name: `Race ${i}` })));
  assert.equal(results.filter((r) => r.status === 201).length, 2);
  for (const r of results.filter((x) => x.status !== 201)) expectRateLimited(r, 24 * 3600);
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM rooms WHERE owner_id = $1', [u!.id])).rows[0].n, 2);
});
