import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { createTestContext, jpeg, photoParts, submitPhoto, upload, type TestContext } from '../support/harness.js';
import {
  deadlines,
  expectError,
  expectStatus,
  listFiles,
  openRound,
  players,
  toVoting,
  viewChallenge,
  vote,
} from '../support/scenario.js';

let ctx: TestContext;
before(async () => {
  ctx = await createTestContext();
});
after(async () => {
  await ctx?.close();
});

const voteRows = async (challengeId: string, voterId?: string) =>
  (
    await ctx.pool.query<{ voter_id: string | null; submission_id: string; cast_at: Date }>(
      voterId
        ? 'SELECT voter_id, submission_id, cast_at FROM votes WHERE challenge_id = $1 AND voter_id = $2'
        : 'SELECT voter_id, submission_id, cast_at FROM votes WHERE challenge_id = $1',
      voterId ? [challengeId, voterId] : [challengeId],
    )
  ).rows;

test('25 concurrent ballots from one voter store exactly one vote; retry is 200, a different target is 409', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!, c!]);
  await toVoting(ctx, a!, round);
  const target = round.subs.get(b!.id).id as string;
  const other = round.subs.get(c!.id).id as string;

  const results = await Promise.all(Array.from({ length: 25 }, () => vote(ctx.api, a!, round.challengeId, target)));
  const statuses = results.map((r) => r.status).sort();
  assert.deepEqual(statuses, [...Array(24).fill(200), 201], JSON.stringify(results.map((r) => r.status)));
  assert.equal(results.filter((r) => r.status === 201).length, 1);
  const castAt = new Set(results.map((r) => r.json.castAtEpochMillis));
  assert.equal(castAt.size, 1, 'every response reports the one stored ballot');
  for (const r of results) {
    assert.equal(r.json.submissionId, target);
    assert.equal(r.json.challengeId, round.challengeId);
  }
  const rows = await voteRows(round.challengeId);
  assert.equal(rows.length, 1);
  assert.equal(rows[0]!.voter_id, a!.id);
  assert.equal(rows[0]!.submission_id, target);

  // Idempotent retry for the same submission.
  const retry = await vote(ctx.api, a!, round.challengeId, target);
  expectStatus(retry, 200);
  assert.equal(retry.json.castAtEpochMillis, rows[0]!.cast_at.getTime());
  // A different submission is refused and changes nothing.
  expectError(await vote(ctx.api, a!, round.challengeId, other), 409, 'ALREADY_VOTED');
  const after = await voteRows(round.challengeId);
  assert.equal(after.length, 1);
  assert.equal(after[0]!.submission_id, target);
  const view = await viewChallenge(ctx.api, a!, round.challengeId);
  assert.equal(view.myStatus.myBallotSubmissionId, target);
  assert.equal(view.myStatus.canVote, false);
});

test('25 concurrent ballots split between two targets: one stored, the rest 200 (same) or 409 (other)', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!, c!]);
  await toVoting(ctx, a!, round);
  const t1 = round.subs.get(a!.id).id as string;
  const t2 = round.subs.get(c!.id).id as string;
  const results = await Promise.all(
    Array.from({ length: 25 }, (_, i) => vote(ctx.api, b!, round.challengeId, i % 2 === 0 ? t1 : t2).then((r) => ({ r, t: i % 2 === 0 ? t1 : t2 }))),
  );
  const created = results.filter((x) => x.r.status === 201);
  assert.equal(created.length, 1, JSON.stringify(results.map((x) => x.r.status)));
  const winner = created[0]!.t;
  for (const { r, t } of results) {
    if (r === created[0]!.r) continue;
    if (t === winner) expectStatus(r, 200);
    else expectError(r, 409, 'ALREADY_VOTED');
  }
  const rows = await voteRows(round.challengeId, b!.id);
  assert.equal(rows.length, 1);
  assert.equal(rows[0]!.submission_id, winner);
});

test('a ballot at or after the voting deadline returns 410 DEADLINE_PASSED; one just before is accepted', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!, c!]);
  await toVoting(ctx, a!, round);
  const { voting } = await deadlines(ctx, round.challengeId);

  ctx.clock.set(voting - 1);
  expectStatus(await vote(ctx.api, a!, round.challengeId, round.subs.get(b!.id).id), 201);

  ctx.clock.set(voting);
  expectError(await vote(ctx.api, b!, round.challengeId, round.subs.get(a!.id).id), 410, 'DEADLINE_PASSED');
  ctx.clock.set(voting + 3600_000);
  expectError(await vote(ctx.api, c!, round.challengeId, round.subs.get(a!.id).id), 410, 'DEADLINE_PASSED');

  const rows = await voteRows(round.challengeId);
  assert.equal(rows.length, 1);
  assert.equal(rows[0]!.voter_id, a!.id);
  const view = await viewChallenge(ctx.api, c!, round.challengeId);
  assert.equal(view.challenge.state, 'COMPLETED');
  assert.equal(view.myStatus.canVote, false);
});

test('a ballot before voting opens is refused with 409 INVALID_TRANSITION', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!]);
  expectError(await vote(ctx.api, a!, round.challengeId, round.subs.get(b!.id).id), 409, 'INVALID_TRANSITION');
  assert.equal((await voteRows(round.challengeId)).length, 0);
});

test('a submission at or after the submission deadline is refused and leaves no trace', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!]);
  const { submission } = await deadlines(ctx, round.challengeId);

  // One millisecond before the deadline: accepted.
  ctx.clock.set(submission - 1);
  expectStatus(await submitPhoto(ctx.api, c!, round.challengeId, '#123456'), 201);

  const filesBefore = listFiles(ctx.mediaRoot);
  const mediaBefore = (await ctx.pool.query('SELECT count(*)::int AS n FROM media')).rows[0].n;
  ctx.clock.set(submission);
  // A new photo, and a replacement of an existing one, are both refused.
  expectError(await submitPhoto(ctx.api, b!, round.challengeId), 410, 'DEADLINE_PASSED');
  expectError(await submitPhoto(ctx.api, a!, round.challengeId, '#ffffff'), 410, 'DEADLINE_PASSED');
  ctx.clock.set(submission + 6 * 3600_000);
  expectError(await submitPhoto(ctx.api, b!, round.challengeId), 410, 'DEADLINE_PASSED');

  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM media')).rows[0].n, mediaBefore);
  assert.deepEqual(listFiles(ctx.mediaRoot), filesBefore, 'refused uploads must not leave objects behind');
  const subs = await ctx.pool.query('SELECT author_id, full_media_id FROM submissions WHERE challenge_id = $1 ORDER BY created_at', [
    round.challengeId,
  ]);
  assert.deepEqual(subs.rows.map((r) => r.author_id), [a!.id, c!.id]);
  assert.equal(subs.rows[0].full_media_id, round.subs.get(a!.id).fullMediaId, 'the original photo is kept');
  const view = await viewChallenge(ctx.api, b!, round.challengeId);
  assert.equal(view.myStatus.canSubmit, false);
  assert.equal(view.myStatus.mySubmissionId, null);
});

test('a submission to a CREATED or CANCELLED challenge is refused', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], []);
  expectStatus(await ctx.api.post(`/v1/challenges/${round.challengeId}/cancel`, a!.token, {}), 200);
  expectError(await submitPhoto(ctx.api, b!, round.challengeId), 409, 'INVALID_TRANSITION');
  const created = await ctx.api.post(`/v1/rooms/${round.roomId}/challenges`, a!.token, { promptId: 'p-002', submissionHours: 1, votingHours: 1 });
  expectStatus(created, 201);
  expectError(
    await upload(ctx.api, b!, created.json.id, photoParts(await jpeg(800, 600), await jpeg(480, 360))),
    409,
    'INVALID_TRANSITION',
  );
});

test('a self-vote returns 422 INVALID_BALLOT and stores nothing', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!, c!]);
  await toVoting(ctx, a!, round);
  for (const p of [a!, b!, c!]) {
    expectError(await vote(ctx.api, p, round.challengeId, round.subs.get(p.id).id), 422, 'INVALID_BALLOT');
  }
  assert.equal((await voteRows(round.challengeId)).length, 0);
  // The voter can still cast a valid ballot afterwards.
  expectStatus(await vote(ctx.api, a!, round.challengeId, round.subs.get(b!.id).id), 201);
});

test('a ballot between a blocked pair returns 422 in both directions', async () => {
  const [a, b, c, d] = await players(ctx.api, 'Ada', 'Ben', 'Cy', 'Dee');
  const round = await openRound(ctx, a!, [b!, c!, d!], [a!, b!, c!]);
  await toVoting(ctx, a!, round);
  // B blocks C.
  expectStatus(await ctx.api.put(`/v1/blocks/${c!.id}`, b!.token), 204);
  expectError(await vote(ctx.api, b!, round.challengeId, round.subs.get(c!.id).id), 422, 'INVALID_BALLOT');
  expectError(await vote(ctx.api, c!, round.challengeId, round.subs.get(b!.id).id), 422, 'INVALID_BALLOT');
  assert.equal((await voteRows(round.challengeId)).length, 0);
  // Unrelated pairs are unaffected.
  expectStatus(await vote(ctx.api, b!, round.challengeId, round.subs.get(a!.id).id), 201);
  expectStatus(await vote(ctx.api, d!, round.challengeId, round.subs.get(c!.id).id), 201);
  // The blocked photo is not even listed for the other party.
  const viewB = await viewChallenge(ctx.api, b!, round.challengeId);
  assert.ok(!viewB.submissions.some((s: any) => s.authorUserId === c!.id));
  const viewC = await viewChallenge(ctx.api, c!, round.challengeId);
  assert.ok(!viewC.submissions.some((s: any) => s.authorUserId === b!.id));
});

test('a ballot for a photo outside the challenge, withdrawn or unknown returns 422', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const r1 = await openRound(ctx, a!, [b!, c!], [a!, b!, c!]);
  const [x, y, z] = await players(ctx.api, 'Xan', 'Yul', 'Zed');
  const r2 = await openRound(ctx, x!, [y!, z!], [x!, y!]);
  const withdrawn = r1.subs.get(c!.id).id as string;
  expectStatus(await ctx.api.del(`/v1/challenges/${r1.challengeId}/submission`, c!.token), 204);
  await toVoting(ctx, a!, r1);
  expectError(await vote(ctx.api, a!, r1.challengeId, withdrawn), 422, 'INVALID_BALLOT');
  expectError(await vote(ctx.api, a!, r1.challengeId, r2.subs.get(x!.id).id), 422, 'INVALID_BALLOT');
  expectError(await vote(ctx.api, a!, r1.challengeId, '00000000-0000-4000-8000-000000000000'), 422, 'INVALID_BALLOT');
  expectError(await vote(ctx.api, a!, r1.challengeId, 'not-a-uuid'), 400, 'VALIDATION');
  assert.equal((await voteRows(r1.challengeId)).length, 0);
});
