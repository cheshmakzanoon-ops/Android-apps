import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { after, before, test } from 'node:test';
import {
  createTestContext,
  guest,
  invite,
  jpeg,
  photoParts,
  submitPhoto,
  upload,
  type TestContext,
} from '../support/harness.js';
import { expectError, expectStatus, listFiles, openRound, players, toVoting, viewChallenge, vote } from '../support/scenario.js';

let ctx: TestContext;
before(async () => {
  ctx = await createTestContext();
});
after(async () => {
  await ctx?.close();
});

test('a non-member cannot read a room, its challenges, invitations or media: 404, never 403', async () => {
  const [a, b, c, d] = await players(ctx.api, 'Ada', 'Ben', 'Cy', 'Dee');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!, c!]);
  await toVoting(ctx, a!, round);
  const bSub = round.subs.get(b!.id);

  // Baseline: a member can read all of it, so the 404s below come from membership alone.
  expectStatus(await ctx.api.get(`/v1/rooms/${round.roomId}`, c!.token), 200);
  expectStatus(await ctx.api.get(`/v1/media/${bSub.fullMediaId}`, c!.token), 200);
  expectStatus(await ctx.api.get(`/v1/media/${bSub.thumbnailMediaId}`, c!.token), 200);

  const missing = await ctx.api.get(`/v1/rooms/${randomUUID()}`, d!.token);
  expectError(missing, 404, 'NOT_FOUND');

  const probes: [string, Promise<any>][] = [
    ['room', ctx.api.get(`/v1/rooms/${round.roomId}`, d!.token)],
    ['room challenges', ctx.api.get(`/v1/rooms/${round.roomId}/challenges`, d!.token)],
    ['room invitations', ctx.api.get(`/v1/rooms/${round.roomId}/invitations`, d!.token)],
    ['create invitation', ctx.api.post(`/v1/rooms/${round.roomId}/invitations`, d!.token, {})],
    ['create challenge', ctx.api.post(`/v1/rooms/${round.roomId}/challenges`, d!.token, { promptId: 'p-001', submissionHours: 1, votingHours: 1 })],
    ['leave', ctx.api.post(`/v1/rooms/${round.roomId}/leave`, d!.token, {})],
    ['delete room', ctx.api.del(`/v1/rooms/${round.roomId}`, d!.token)],
    ['remove member', ctx.api.del(`/v1/rooms/${round.roomId}/members/${b!.id}`, d!.token)],
    ['challenge', ctx.api.get(`/v1/challenges/${round.challengeId}`, d!.token)],
    ['cancel', ctx.api.post(`/v1/challenges/${round.challengeId}/cancel`, d!.token, {})],
    ['full media', ctx.api.get(`/v1/media/${bSub.fullMediaId}`, d!.token)],
    ['thumbnail media', ctx.api.get(`/v1/media/${bSub.thumbnailMediaId}`, d!.token)],
    ['ballot', vote(ctx.api, d!, round.challengeId, bSub.id)],
    ['delete submission', ctx.api.del(`/v1/submissions/${bSub.id}`, d!.token)],
    ['report submission', ctx.api.post('/v1/reports', d!.token, { targetType: 'SUBMISSION', targetId: bSub.id, reason: 'SPAM' })],
    ['report user', ctx.api.post('/v1/reports', d!.token, { targetType: 'USER', targetId: b!.id, reason: 'SPAM' })],
    ['block user', ctx.api.put(`/v1/blocks/${b!.id}`, d!.token)],
  ];
  for (const [name, p] of probes) {
    const r = await p;
    assert.equal(r.status, 404, `${name}: expected 404, got ${r.status} ${JSON.stringify(r.json)}`);
    assert.equal(r.json.error.code, 'NOT_FOUND', name);
    // The body is the same as for an id that does not exist: membership cannot be probed.
    assert.deepEqual(r.json, missing.json, name);
    assert.equal(r.headers['content-type']?.toString().startsWith('image/'), false, name);
  }

  const mine = await ctx.api.get('/v1/rooms', d!.token);
  expectStatus(mine, 200);
  assert.deepEqual(mine.json.rooms, []);

  // Unauthenticated and forged bearer tokens get 401 on media, not bytes.
  expectError(await ctx.api.get(`/v1/media/${bSub.fullMediaId}`), 401, 'UNAUTHENTICATED');
  const forged = await ctx.api.call('GET', `/v1/media/${bSub.fullMediaId}`, { headers: { authorization: `Bearer ${'A'.repeat(43)}` } });
  expectError(forged, 401, 'UNAUTHENTICATED');

  // Nothing changed for the members.
  const view = await viewChallenge(ctx.api, a!, round.challengeId);
  assert.equal(view.submissions.length, 3);
  const votes = await ctx.pool.query('SELECT count(*)::int AS n FROM votes WHERE challenge_id = $1', [round.challengeId]);
  assert.equal(votes.rows[0].n, 0);
});

test('a non-member cannot submit; a member who joined after the start is not on the roster', async () => {
  const [a, b, c, d, e] = await players(ctx.api, 'Ada', 'Ben', 'Cy', 'Dee', 'Eve');
  const round = await openRound(ctx, a!, [b!, c!], [a!]);
  const filesBefore = listFiles(ctx.mediaRoot);
  const mediaBefore = (await ctx.pool.query('SELECT count(*)::int AS n FROM media')).rows[0].n;

  // Outsider: indistinguishable from a challenge that does not exist.
  expectError(await submitPhoto(ctx.api, d!, round.challengeId), 404, 'NOT_FOUND');
  expectError(await submitPhoto(ctx.api, d!, randomUUID()), 404, 'NOT_FOUND');
  expectError(await ctx.api.del(`/v1/challenges/${round.challengeId}/submission`, d!.token), 404, 'NOT_FOUND');

  // Late joiner: may see the room, but the roster was frozen at start.
  const token = await invite(ctx.api, a!, round.roomId);
  expectStatus(await ctx.api.post(`/v1/invitations/${token}/join`, e!.token), 200);
  expectError(await submitPhoto(ctx.api, e!, round.challengeId), 403, 'FORBIDDEN');
  const view = await viewChallenge(ctx.api, e!, round.challengeId);
  assert.equal(view.myStatus.isRosterMember, false);
  assert.equal(view.myStatus.canSubmit, false);
  assert.ok(!view.roster.some((r: any) => r.userId === e!.id));

  // Neither attempt stored a row or left an object behind.
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM media')).rows[0].n, mediaBefore);
  assert.deepEqual(listFiles(ctx.mediaRoot), filesBefore);
  const subs = await ctx.pool.query('SELECT author_id FROM submissions WHERE challenge_id = $1', [round.challengeId]);
  assert.deepEqual(subs.rows.map((r) => r.author_id), [a!.id]);
});

test('a late joiner cannot vote: 403 FORBIDDEN, no ballot stored', async () => {
  const [a, b, c, e] = await players(ctx.api, 'Ada', 'Ben', 'Cy', 'Eve');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!]);
  const token = await invite(ctx.api, a!, round.roomId);
  expectStatus(await ctx.api.post(`/v1/invitations/${token}/join`, e!.token), 200);
  await toVoting(ctx, a!, round);
  expectError(await vote(ctx.api, e!, round.challengeId, round.subs.get(a!.id).id), 403, 'FORBIDDEN');
  const n = await ctx.pool.query('SELECT count(*)::int AS n FROM votes WHERE challenge_id = $1', [round.challengeId]);
  assert.equal(n.rows[0].n, 0);
});

test('authorId, ownerId, voterId and role in request bodies are rejected and never trusted', async () => {
  const [a, b, c, x] = await players(ctx.api, 'Ada', 'Ben', 'Cy', 'Xan');

  // Room creation with a forged owner.
  const forgedRoom = await ctx.api.post('/v1/rooms', x!.token, { name: 'Mine', ownerId: a!.id });
  expectError(forgedRoom, 400, 'VALIDATION');
  assert.match(forgedRoom.json.error.message, /Unknown field: ownerId/);
  expectError(await ctx.api.post('/v1/rooms', x!.token, { name: 'Mine', role: 'OWNER' }), 400, 'VALIDATION');
  const rooms = await ctx.pool.query('SELECT count(*)::int AS n FROM rooms WHERE owner_id = $1', [a!.id]);
  assert.equal(rooms.rows[0].n, 0);

  const room = await ctx.api.post('/v1/rooms', a!.token, { name: 'Real' });
  expectStatus(room, 201);
  const roomId = room.json.id as string;
  const token = await invite(ctx.api, a!, roomId);

  // Joining with a requested role: rejected; a plain join is always MEMBER.
  const forgedJoin = await ctx.api.post(`/v1/invitations/${token}/join`, b!.token, { role: 'OWNER' });
  expectError(forgedJoin, 400, 'VALIDATION');
  expectError(await ctx.api.post(`/v1/invitations/${token}/join`, b!.token, { userId: c!.id }), 400, 'VALIDATION');
  const joined = await ctx.api.post(`/v1/invitations/${token}/join`, b!.token);
  expectStatus(joined, 200);
  assert.equal(joined.json.viewerRole, 'MEMBER');
  expectStatus(await ctx.api.post(`/v1/invitations/${token}/join`, c!.token), 200);
  const roles = await ctx.pool.query('SELECT user_id, role FROM room_members WHERE room_id = $1 AND left_at IS NULL', [roomId]);
  assert.deepEqual(
    Object.fromEntries(roles.rows.map((r) => [r.user_id, r.role])),
    { [a!.id]: 'OWNER', [b!.id]: 'MEMBER', [c!.id]: 'MEMBER' },
  );

  // A member cannot act as owner by naming one.
  expectError(
    await ctx.api.post(`/v1/rooms/${roomId}/challenges`, b!.token, { promptId: 'p-001', submissionHours: 1, votingHours: 1, createdBy: a!.id }),
    400,
    'VALIDATION',
  );
  expectError(
    await ctx.api.post(`/v1/rooms/${roomId}/challenges`, b!.token, { promptId: 'p-001', submissionHours: 1, votingHours: 1 }),
    403,
    'FORBIDDEN',
  );
  const created = await ctx.api.post(`/v1/rooms/${roomId}/challenges`, a!.token, { promptId: 'p-001', submissionHours: 1, votingHours: 1 });
  expectStatus(created, 201);
  const challengeId = created.json.id as string;
  expectError(await ctx.api.post(`/v1/challenges/${challengeId}/start`, b!.token, {}), 403, 'FORBIDDEN');
  expectError(await ctx.api.post(`/v1/challenges/${challengeId}/start`, a!.token, { ownerId: a!.id }), 400, 'VALIDATION');
  expectStatus(await ctx.api.post(`/v1/challenges/${challengeId}/start`, a!.token, {}), 200);

  // Uploads: an extra form field (text or file) naming another author is rejected.
  const full = await jpeg(800, 600);
  const thumb = await jpeg(480, 360);
  const withField = await upload(ctx.api, b!, challengeId, [...photoParts(full, thumb), { name: 'authorId', data: Buffer.from(a!.id) }]);
  expectError(withField, 400, 'VALIDATION');
  const withFile = await upload(ctx.api, b!, challengeId, [
    { name: 'authorId', data: Buffer.from(a!.id), filename: 'a.txt', contentType: 'text/plain' },
    ...photoParts(full, thumb),
  ]);
  expectError(withFile, 400, 'VALIDATION');
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM submissions WHERE challenge_id = $1', [challengeId])).rows[0].n, 0);

  // The author of an accepted upload is always the caller.
  const ok = await upload(ctx.api, b!, challengeId, photoParts(full, thumb));
  expectStatus(ok, 201);
  assert.equal(ok.json.authorUserId, b!.id);
  assert.equal(ok.json.isMine, true);
  const stored = await ctx.pool.query('SELECT author_id FROM submissions WHERE id = $1', [ok.json.id]);
  assert.equal(stored.rows[0].author_id, b!.id);
  const media = await ctx.pool.query('SELECT owner_id FROM media WHERE id = ANY($1::uuid[])', [[ok.json.fullMediaId, ok.json.thumbnailMediaId]]);
  assert.deepEqual(media.rows.map((r) => r.owner_id), [b!.id, b!.id]);
  expectStatus(await submitPhoto(ctx.api, c!, challengeId, '#119911'), 201);

  // Ballots with a forged voter.
  ctx.clock.set((await ctx.pool.query('SELECT submission_deadline FROM challenges WHERE id = $1', [challengeId])).rows[0].submission_deadline.getTime());
  const forgedBallot = await ctx.api.post(`/v1/challenges/${challengeId}/ballot`, b!.token, { submissionId: ok.json.id, voterId: c!.id });
  expectError(forgedBallot, 400, 'VALIDATION');
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM votes WHERE challenge_id = $1', [challengeId])).rows[0].n, 0);
  const real = await ctx.api.post(`/v1/challenges/${challengeId}/ballot`, c!.token, { submissionId: ok.json.id });
  expectStatus(real, 201);
  const ballots = await ctx.pool.query('SELECT voter_id FROM votes WHERE challenge_id = $1', [challengeId]);
  assert.deepEqual(ballots.rows.map((r) => r.voter_id), [c!.id]);

  // Profile and reports with forged identities.
  expectError(await ctx.api.patch('/v1/me', x!.token, { displayName: 'Hacker', id: a!.id }), 400, 'VALIDATION');
  const profileA = await ctx.api.get('/v1/me', a!.token);
  assert.equal(profileA.json.displayName, 'Ada');
  expectError(
    await ctx.api.post('/v1/reports', b!.token, { targetType: 'USER', targetId: c!.id, reason: 'SPAM', reporterId: a!.id }),
    400,
    'VALIDATION',
  );
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM reports')).rows[0].n, 0);
});

test('guest creation rejects a client-chosen user id', async () => {
  const r = await ctx.api.call('POST', '/v1/identity/guest', { body: { displayName: 'Mallory', id: randomUUID() }, ip: '10.200.0.1' });
  expectError(r, 400, 'VALIDATION');
  const g = await guest(ctx.api, 'Mallory');
  const me = await ctx.api.get('/v1/me', g.token);
  expectStatus(me, 200);
  assert.equal(me.json.id, g.id);
});
