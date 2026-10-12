import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import { after, before, test } from 'node:test';
import { runWorkerOnce } from '../../src/worker.js';
import { createTestContext, HOUR, jpeg, multipart, photoParts, type TestContext } from '../support/harness.js';

/**
 * Full multi-user lifecycle over real HTTP. The app is the same Fastify instance server.ts builds
 * (buildApp), listening on a random local port, with an injected manual clock so deadlines can be
 * passed without waiting. Three members (A owner, B, C) and an outsider D.
 */

let ctx: TestContext;
let base: string;

before(async () => {
  ctx = await createTestContext();
  await ctx.app.listen({ host: '127.0.0.1', port: 0 });
  const addr = ctx.app.server.address() as AddressInfo;
  base = `http://127.0.0.1:${addr.port}`;
});
after(async () => {
  await ctx?.close();
});

interface HttpRes {
  status: number;
  json: any;
  headers: Headers;
  bytes: Buffer;
}

async function http(
  method: string,
  path: string,
  opts: { token?: string; body?: unknown; raw?: Buffer; headers?: Record<string, string> } = {},
): Promise<HttpRes> {
  const headers: Record<string, string> = { ...(opts.headers ?? {}) };
  if (opts.token) headers.authorization = `Bearer ${opts.token}`;
  let body: Buffer | string | undefined;
  if (opts.body !== undefined) {
    body = JSON.stringify(opts.body);
    headers['content-type'] = 'application/json';
  } else if (opts.raw) {
    body = opts.raw;
  }
  const res = await fetch(base + path, { method, headers, ...(body !== undefined ? { body } : {}) });
  const bytes = Buffer.from(await res.arrayBuffer());
  const ct = res.headers.get('content-type') ?? '';
  const json = ct.includes('application/json') && bytes.length > 0 ? JSON.parse(bytes.toString('utf8')) : null;
  // api-contract.md: every response carries a Date header (the client derives its clock offset).
  assert.ok(res.headers.get('date'), `${method} ${path} has a Date header`);
  return { status: res.status, json, headers: res.headers, bytes };
}

function ok(r: HttpRes, status: number, label: string): any {
  assert.equal(r.status, status, `${label}: expected ${status}, got ${r.status} ${JSON.stringify(r.json)}`);
  return r.json;
}
function err(r: HttpRes, status: number, code: string, label: string): void {
  assert.equal(r.status, status, `${label}: expected ${status} ${code}, got ${r.status} ${JSON.stringify(r.json)}`);
  assert.equal(r.json?.error?.code, code, label);
}

interface User {
  name: string;
  id: string;
  token: string;
}

async function signUp(name: string): Promise<User> {
  const r = ok(await http('POST', '/v1/identity/guest', { body: { displayName: name } }), 201, `guest ${name}`);
  assert.equal(r.user.displayName, name);
  assert.match(r.session.token, /^[A-Za-z0-9_-]{43}$/);
  return { name, id: r.user.id, token: r.session.token };
}

async function uploadPhoto(u: User, challengeId: string, color: string): Promise<HttpRes> {
  const mp = multipart(photoParts(await jpeg(1200, 900, color), await jpeg(480, 360, color)));
  return http('PUT', `/v1/challenges/${challengeId}/submission`, { token: u.token, raw: mp.payload, headers: mp.headers });
}

const view = async (u: User, challengeId: string) => ok(await http('GET', `/v1/challenges/${challengeId}`, { token: u.token }), 200, `view ${u.name}`);
const ballot = (u: User, challengeId: string, submissionId: string) =>
  http('POST', `/v1/challenges/${challengeId}/ballot`, { token: u.token, body: { submissionId } });

/** Runs one round from creation to result. Returns the challenge id, submissions by user and the result. */
async function playRound(
  owner: User,
  members: User[],
  outsider: User,
  roomId: string,
  promptId: string,
  votes: (subs: Map<string, string>) => [User, string][],
): Promise<{ challengeId: string; subs: Map<string, string>; result: any }> {
  const created = ok(
    await http('POST', `/v1/rooms/${roomId}/challenges`, { token: owner.token, body: { promptId, submissionHours: 1, votingHours: 1 } }),
    201,
    'create challenge',
  );
  assert.equal(created.state, 'CREATED');
  assert.equal(created.submissionDeadlineEpochMillis, null);
  const challengeId = created.id as string;

  const started = ok(await http('POST', `/v1/challenges/${challengeId}/start`, { token: owner.token, body: {} }), 200, 'start');
  assert.equal(started.state, 'OPEN_FOR_SUBMISSIONS');
  assert.equal(started.serverTimeEpochMillis, ctx.clock.now());
  assert.equal(started.submissionDeadlineEpochMillis, ctx.clock.now() + HOUR);
  assert.equal(started.votingDeadlineEpochMillis, ctx.clock.now() + 2 * HOUR);

  const everyone = [owner, ...members];
  const subs = new Map<string, string>();
  const media = new Map<string, string>();
  const colors = ['#d04040', '#40d040', '#4040d0'];
  for (const [i, u] of everyone.entries()) {
    const s = ok(await uploadPhoto(u, challengeId, colors[i]!), 201, `upload ${u.name}`);
    assert.equal(s.authorUserId, u.id);
    assert.equal(s.isMine, true);
    assert.equal(s.status, 'ACTIVE');
    subs.set(u.id, s.id);
    media.set(u.id, s.thumbnailMediaId);
  }
  err(await uploadPhoto(outsider, challengeId, '#000000'), 404, 'NOT_FOUND', 'outsider upload');

  // While submissions are open each member sees only their own photo, and others' media is hidden.
  for (const u of everyone) {
    const v = await view(u, challengeId);
    assert.equal(v.challenge.state, 'OPEN_FOR_SUBMISSIONS');
    assert.deepEqual(v.submissions.map((s: any) => s.id), [subs.get(u.id)]);
    assert.equal(v.myStatus.canSubmit, true);
    assert.equal(v.myStatus.canVote, false);
  }
  err(await http('GET', `/v1/media/${media.get(members[0]!.id)}`, { token: owner.token }), 404, 'NOT_FOUND', 'early media');

  // Voting window.
  ctx.clock.advance(HOUR);
  for (const u of everyone) {
    const v = await view(u, challengeId);
    assert.equal(v.challenge.state, 'VOTING');
    assert.equal(v.submissions.length, everyone.length);
    assert.equal(v.myStatus.canVote, true);
    assert.equal(v.submissions.filter((s: any) => s.isMine).length, 1);
  }
  for (const u of everyone) {
    for (const other of everyone) {
      const r = await http('GET', `/v1/media/${media.get(other.id)}`, { token: u.token });
      ok(r, 200, `${u.name} reads ${other.name}'s photo`);
      assert.equal(r.headers.get('content-type'), 'image/jpeg');
      assert.equal(r.headers.get('cache-control'), 'private, max-age=300');
      assert.equal(r.bytes[0], 0xff);
      assert.equal(r.bytes[1], 0xd8);
    }
    err(await http('GET', `/v1/media/${media.get(u.id)}`, { token: outsider.token }), 404, 'NOT_FOUND', 'outsider media');
  }
  err(await ballot(owner, challengeId, subs.get(owner.id)!), 422, 'INVALID_BALLOT', 'self-vote');
  err(await ballot(outsider, challengeId, subs.get(owner.id)!), 404, 'NOT_FOUND', 'outsider ballot');

  for (const [voter, target] of votes(subs)) {
    const b = ok(await ballot(voter, challengeId, target), 201, `ballot ${voter.name}`);
    assert.equal(b.submissionId, target);
    // A retry is idempotent.
    const again = ok(await ballot(voter, challengeId, target), 200, `retry ${voter.name}`);
    assert.equal(again.castAtEpochMillis, b.castAtEpochMillis);
  }

  // Close voting. The background worker computes the result; reads then agree.
  ctx.clock.advance(HOUR);
  const stats = await runWorkerOnce(ctx.deps);
  assert.ok(stats.advanced >= 1);
  const results = [];
  for (const u of everyone) {
    const v = await view(u, challengeId);
    assert.equal(v.challenge.state, 'COMPLETED');
    assert.equal(v.myStatus.canVote, false);
    assert.ok(v.result);
    results.push(v.result);
  }
  for (const r of results.slice(1)) assert.deepEqual(r, results[0], 'A, B and C see the identical result');
  err(await http('GET', `/v1/challenges/${challengeId}`, { token: outsider.token }), 404, 'NOT_FOUND', 'outsider result');
  err(await ballot(members[1]!, challengeId, subs.get(owner.id)!), 410, 'DEADLINE_PASSED', 'late ballot');
  return { challengeId, subs, result: results[0] };
}

test('three members and an outsider through two full rounds over HTTP', async () => {
  const health = ok(await http('GET', '/v1/health'), 200, 'health');
  assert.equal(health.status, 'ok');
  assert.equal(health.database, 'ok');

  const A = await signUp('Ada');
  const B = await signUp('Ben');
  const C = await signUp('Cyd');
  const D = await signUp('Dot');
  err(await http('GET', '/v1/me'), 401, 'UNAUTHENTICATED', 'no token');

  const prompts = ok(await http('GET', '/v1/prompts', { token: A.token }), 200, 'prompts');
  assert.equal(prompts.prompts.length, 30);

  // Room and invitation.
  const room = ok(await http('POST', '/v1/rooms', { token: A.token, body: { name: 'Sunday walkers' } }), 201, 'room');
  assert.equal(room.viewerRole, 'OWNER');
  const roomId = room.id as string;
  const inv = ok(await http('POST', `/v1/rooms/${roomId}/invitations`, { token: A.token, body: { ttlHours: 72 } }), 201, 'invite');
  const pre = ok(await http('GET', `/v1/invitations/${inv.token}`, { token: B.token }), 200, 'preview');
  assert.deepEqual(
    { roomName: pre.roomName, ownerDisplayName: pre.ownerDisplayName, memberCount: pre.memberCount },
    { roomName: 'Sunday walkers', ownerDisplayName: 'Ada', memberCount: 1 },
  );
  // People join a minute apart (the manual clock does not move on its own).
  ctx.clock.advance(60_000);
  ok(await http('POST', `/v1/invitations/${inv.token}/join`, { token: B.token }), 200, 'B joins');
  ctx.clock.advance(60_000);

  // Starting needs three members.
  const early = ok(
    await http('POST', `/v1/rooms/${roomId}/challenges`, { token: A.token, body: { promptId: prompts.prompts[0].id, submissionHours: 1, votingHours: 1 } }),
    201,
    'early challenge',
  );
  err(await http('POST', `/v1/challenges/${early.id}/start`, { token: A.token, body: {} }), 409, 'CONFLICT', 'start with two');
  ok(await http('POST', `/v1/challenges/${early.id}/cancel`, { token: A.token, body: {} }), 200, 'cancel early');

  const joined = ok(await http('POST', `/v1/invitations/${inv.token}/join`, { token: C.token }), 200, 'C joins');
  assert.deepEqual(joined.members.map((m: any) => m.displayName), ['Ada', 'Ben', 'Cyd']);
  ok(await http('POST', `/v1/invitations/${inv.token}/join`, { token: C.token }), 200, 'C joins again');

  // D is outside the room.
  err(await http('GET', `/v1/rooms/${roomId}`, { token: D.token }), 404, 'NOT_FOUND', 'D room');
  assert.deepEqual(ok(await http('GET', '/v1/rooms', { token: D.token }), 200, 'D rooms').rooms, []);

  // Round 1: B wins (A and C vote for B, B votes for A).
  const r1 = await playRound(A, [B, C], D, roomId, prompts.prompts[1].id, (s) => [
    [A, s.get(B.id)!],
    [B, s.get(A.id)!],
    [C, s.get(B.id)!],
  ]);
  assert.equal(r1.result.outcome, 'WINNER');
  assert.deepEqual(r1.result.coWinnerSubmissionIds, [r1.subs.get(B.id)]);
  assert.equal(r1.result.ballotCount, 3);
  assert.equal(r1.result.eligibleVoterCount, 3);
  const t1 = Object.fromEntries(r1.result.tallies.map((t: any) => [t.submissionId, t.votes]));
  assert.deepEqual(t1, { [r1.subs.get(A.id)!]: 1, [r1.subs.get(B.id)!]: 2, [r1.subs.get(C.id)!]: 0 });

  // Rematch, round 2: a three-way tie (each votes for the next).
  const r2 = await playRound(A, [B, C], D, roomId, prompts.prompts[2].id, (s) => [
    [A, s.get(B.id)!],
    [B, s.get(C.id)!],
    [C, s.get(A.id)!],
  ]);
  assert.equal(r2.result.outcome, 'TIE');
  assert.deepEqual(r2.result.coWinnerSubmissionIds, [...r2.subs.values()].sort());
  assert.equal(r2.result.ballotCount, 3);

  // Room challenge list: newest first, paged.
  const page1 = ok(await http('GET', `/v1/rooms/${roomId}/challenges?limit=2`, { token: B.token }), 200, 'page 1');
  assert.deepEqual(page1.items.map((c: any) => c.roundNumber), [3, 2]);
  assert.ok(page1.nextCursor);
  const page2 = ok(await http('GET', `/v1/rooms/${roomId}/challenges?limit=2&cursor=${page1.nextCursor}`, { token: B.token }), 200, 'page 2');
  assert.deepEqual(page2.items.map((c: any) => [c.roundNumber, c.state]), [[1, 'CANCELLED']]);
  assert.equal(page2.nextCursor, null);

  // History: both completed rounds for each member, with the same results.
  for (const u of [A, B, C]) {
    const h = ok(await http('GET', '/v1/me/history', { token: u.token }), 200, `history ${u.name}`);
    const played = h.items.filter((i: any) => i.challenge.state === 'COMPLETED');
    assert.deepEqual(played.map((i: any) => i.challenge.id), [r2.challengeId, r1.challengeId], `${u.name} history order`);
    assert.deepEqual(played[0].result, r2.result);
    assert.deepEqual(played[1].result, r1.result);
    assert.equal(played[0].mySubmissionId, r2.subs.get(u.id));
    assert.equal(played[1].mySubmissionId, r1.subs.get(u.id));
    assert.equal(played[0].roomName, 'Sunday walkers');
  }
  // D has no history, and joining afterwards does not reveal rounds played before D joined.
  assert.deepEqual(ok(await http('GET', '/v1/me/history', { token: D.token }), 200, 'D history').items, []);
  ok(await http('POST', `/v1/invitations/${inv.token}/join`, { token: D.token }), 200, 'D joins later');
  const hd = ok(await http('GET', '/v1/me/history', { token: D.token }), 200, 'D history after join');
  assert.deepEqual(hd.items, []);
  const v = await view(D, r2.challengeId);
  assert.equal(v.myStatus.isRosterMember, false);
  assert.deepEqual(v.result, r2.result, 'a later member reads the same published result');
});
