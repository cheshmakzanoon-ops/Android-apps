import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { createTestContext, type TestContext } from '../support/harness.js';
import {
  auditRows,
  expectError,
  expectStatus,
  mod,
  openRound,
  players,
  storedMedia,
  toCompleted,
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

const report = (token: string, targetType: 'SUBMISSION' | 'USER', targetId: string, reason = 'INAPPROPRIATE') =>
  ctx.api.post('/v1/reports', token, { targetType, targetId, reason });

const submissionStatus = async (id: string) =>
  (await ctx.pool.query<{ status: string; hidden_reason: string | null }>('SELECT status, hidden_reason FROM submissions WHERE id = $1', [id])).rows[0]!;

test('three distinct reporters auto-hide a submission; repeats by one reporter do not count', async () => {
  const [a, b, r1, r2, r3, v] = await players(ctx.api, 'Ada', 'Ben', 'Rae', 'Ron', 'Rio', 'Vic');
  const round = await openRound(ctx, a!, [b!, r1!, r2!, r3!, v!], [a!, b!, v!]);
  await toVoting(ctx, a!, round);
  const target = round.subs.get(a!.id);

  // The author cannot report their own photo.
  expectError(await report(a!.token, 'SUBMISSION', target.id), 422, 'VALIDATION');

  expectStatus(await report(r1!.token, 'SUBMISSION', target.id), 201);
  // The same reporter again: 200, still one open report, not a second vote toward hiding.
  expectStatus(await report(r1!.token, 'SUBMISSION', target.id, 'SPAM'), 200);
  expectStatus(await report(r1!.token, 'SUBMISSION', target.id), 200);
  expectStatus(await report(r2!.token, 'SUBMISSION', target.id), 201);
  assert.equal((await submissionStatus(target.id)).status, 'ACTIVE', 'two reporters are not enough');
  assert.equal((await auditRows(ctx, 'SUBMISSION_AUTO_HIDDEN')).length, 0);

  expectStatus(await report(r3!.token, 'SUBMISSION', target.id), 201);
  const s = await submissionStatus(target.id);
  assert.equal(s.status, 'HIDDEN');
  assert.equal(s.hidden_reason, 'AUTO_REPORT_THRESHOLD');
  const audit = await auditRows(ctx, 'SUBMISSION_AUTO_HIDDEN');
  assert.equal(audit.length, 1);
  assert.equal(audit[0].actor, 'system');
  assert.equal(audit[0].target_id, target.id);
  assert.equal(audit[0].details.reporters, 3);
  // Reporter identities are never part of the audit details.
  for (const p of [r1!, r2!, r3!]) assert.equal(JSON.stringify(audit[0].details).includes(p.id), false);
  const open = await ctx.pool.query(
    `SELECT count(*)::int AS n, count(DISTINCT reporter_id)::int AS d FROM reports WHERE target_id = $1 AND status = 'OPEN'`,
    [target.id],
  );
  assert.deepEqual(open.rows[0], { n: 3, d: 3 });

  // Hidden: excluded from galleries, media and ballots for everyone but the author.
  const viewV = await viewChallenge(ctx.api, v!, round.challengeId);
  assert.ok(!viewV.submissions.some((x: any) => x.id === target.id));
  expectError(await ctx.api.get(`/v1/media/${target.fullMediaId}`, v!.token), 404, 'NOT_FOUND');
  expectError(await vote(ctx.api, v!, round.challengeId, target.id), 422, 'INVALID_BALLOT');
  const viewA = await viewChallenge(ctx.api, a!, round.challengeId);
  const own = viewA.submissions.find((x: any) => x.id === target.id);
  assert.equal(own?.status, 'HIDDEN');
  expectStatus(await ctx.api.get(`/v1/media/${target.fullMediaId}`, a!.token), 200);

  // A fourth report does not create a second audit entry.
  expectStatus(await report(v!.token, 'SUBMISSION', target.id), 201);
  assert.equal((await auditRows(ctx, 'SUBMISSION_AUTO_HIDDEN')).length, 1);

  // At close, the hidden photo is not in the tally.
  expectStatus(await vote(ctx.api, a!, round.challengeId, round.subs.get(b!.id).id), 201);
  expectStatus(await vote(ctx.api, b!, round.challengeId, round.subs.get(v!.id).id), 201);
  const done = await toCompleted(ctx, v!, round);
  assert.ok(!done.result.tallies.some((t: any) => t.submissionId === target.id));
});

test('reports require a shared room, and outsiders cannot learn whether a target exists', async () => {
  const [a, b, c, outsider] = await players(ctx.api, 'Ada', 'Ben', 'Cy', 'Out');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!]);
  // Before voting, other members' photos are invisible, so they cannot be reported either.
  expectError(await report(c!.token, 'SUBMISSION', round.subs.get(a!.id).id), 404, 'NOT_FOUND');
  expectError(await report(outsider!.token, 'USER', a!.id), 404, 'NOT_FOUND');
  expectError(await report(outsider!.token, 'SUBMISSION', round.subs.get(a!.id).id), 404, 'NOT_FOUND');
  expectError(await report(b!.token, 'USER', b!.id), 422, 'VALIDATION');
  expectStatus(await report(b!.token, 'USER', a!.id, 'HARASSMENT'), 201);
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM reports WHERE reporter_id = $1', [outsider!.id])).rows[0].n, 0);
});

test('every moderation CLI action writes audit_log and moderation_actions rows naming the operator', async () => {
  const [a, b, c, d] = await players(ctx.api, 'Ada', 'Ben', 'Cy', 'Dee');
  const round = await openRound(ctx, a!, [b!, c!, d!], [a!, b!]);
  await toVoting(ctx, a!, round);
  const sub = round.subs.get(b!.id);
  expectStatus(await report(c!.token, 'SUBMISSION', sub.id), 201);
  expectStatus(await report(d!.token, 'USER', b!.id), 201);
  const reports = await ctx.pool.query<{ id: string; target_type: string }>('SELECT id, target_type FROM reports WHERE reporter_id = ANY($1::uuid[]) ORDER BY target_type', [
    [c!.id, d!.id],
  ]);
  const subReport = reports.rows.find((r) => r.target_type === 'SUBMISSION')!.id;
  const userReport = reports.rows.find((r) => r.target_type === 'USER')!.id;
  const op = 'ops.alice';
  const actor = `operator:${op}`;
  const auditBefore = (await auditRows(ctx)).length;

  const steps: { argv: string[]; action: string; targetType: string; targetId: string; check?: () => Promise<void> }[] = [
    { argv: ['list-reports'], action: 'LIST_REPORTS', targetType: 'REPORT', targetId: '*' },
    { argv: ['list-reports', '--status', 'ALL'], action: 'LIST_REPORTS', targetType: 'REPORT', targetId: '*' },
    { argv: ['show-report', subReport], action: 'SHOW_REPORT', targetType: 'REPORT', targetId: subReport },
    {
      argv: ['hide-submission', sub.id, '--reason', 'nudity'],
      action: 'HIDE_SUBMISSION',
      targetType: 'SUBMISSION',
      targetId: sub.id,
      check: async () => {
        assert.equal((await submissionStatus(sub.id)).status, 'HIDDEN');
        expectError(await ctx.api.get(`/v1/media/${sub.fullMediaId}`, c!.token), 404, 'NOT_FOUND');
        const r = await ctx.pool.query('SELECT status, resolved_by FROM reports WHERE id = $1', [subReport]);
        assert.deepEqual(r.rows[0], { status: 'ACTIONED', resolved_by: op });
      },
    },
    {
      argv: ['restore-submission', sub.id],
      action: 'RESTORE_SUBMISSION',
      targetType: 'SUBMISSION',
      targetId: sub.id,
      check: async () => {
        assert.equal((await submissionStatus(sub.id)).status, 'ACTIVE');
        expectStatus(await ctx.api.get(`/v1/media/${sub.fullMediaId}`, c!.token), 200);
      },
    },
    {
      argv: ['dismiss-report', userReport],
      action: 'DISMISS_REPORT',
      targetType: 'REPORT',
      targetId: userReport,
      check: async () => {
        const r = await ctx.pool.query('SELECT status FROM reports WHERE id = $1', [userReport]);
        assert.equal(r.rows[0].status, 'DISMISSED');
      },
    },
    {
      argv: ['suspend-user', b!.id, '--reason', 'spam'],
      action: 'SUSPEND_USER',
      targetType: 'USER',
      targetId: b!.id,
      check: async () => expectError(await ctx.api.get('/v1/me', b!.token), 401, 'UNAUTHENTICATED'),
    },
    {
      argv: ['restore-user', b!.id],
      action: 'RESTORE_USER',
      targetType: 'USER',
      targetId: b!.id,
      check: async () => expectStatus(await ctx.api.get('/v1/me', b!.token), 200),
    },
    {
      argv: ['purge-room', round.roomId],
      action: 'PURGE_ROOM',
      targetType: 'ROOM',
      targetId: round.roomId,
      check: async () => {
        expectError(await ctx.api.get(`/v1/rooms/${round.roomId}`, a!.token), 404, 'NOT_FOUND');
        expectError(await ctx.api.get(`/v1/challenges/${round.challengeId}`, a!.token), 404, 'NOT_FOUND');
        for (const id of [sub.fullMediaId, sub.thumbnailMediaId]) {
          const m = await storedMedia(ctx, id);
          assert.equal(m.purged, true);
          assert.equal(m.bytes, null);
        }
      },
    },
  ];

  for (const step of steps) {
    const before = (await auditRows(ctx)).length;
    const r = await mod(ctx, ...step.argv, '--operator', op);
    assert.equal(r.code, 0, `${step.argv[0]}: ${r.err.join('\n')}`);
    const rows = await auditRows(ctx);
    assert.equal(rows.length, before + 1, `${step.argv[0]} writes exactly one audit row`);
    const last = rows[rows.length - 1];
    assert.equal(last.actor, actor);
    assert.equal(last.action, step.action);
    assert.equal(last.target_type, step.targetType);
    assert.equal(last.target_id, step.targetId);
    const ma = await ctx.pool.query(
      'SELECT operator, action, target_type, target_id FROM moderation_actions WHERE action = $1 ORDER BY created_at DESC, id LIMIT 1',
      [step.action],
    );
    assert.deepEqual(ma.rows[0], { operator: op, action: step.action, target_type: step.targetType, target_id: step.targetId });
    await step.check?.();
  }
  assert.equal((await auditRows(ctx)).length, auditBefore + steps.length);
  const reasons = (await auditRows(ctx, 'HIDE_SUBMISSION'))[0].details;
  assert.equal(reasons.reason, 'nudity');

  // Refused commands write nothing: missing reason, unknown target, missing operator, bad id.
  const before = (await auditRows(ctx)).length;
  assert.equal((await mod(ctx, 'hide-submission', sub.id, '--operator', op)).code, 2);
  assert.equal((await mod(ctx, 'suspend-user', '00000000-0000-4000-8000-000000000000', '--reason', 'x', '--operator', op)).code, 1);
  assert.equal((await mod(ctx, 'dismiss-report', userReport, '--operator', op)).code, 1, 'already dismissed');
  assert.equal((await mod(ctx, 'show-report', 'not-an-id', '--operator', op)).code, 2);
  assert.equal((await mod(ctx, 'list-reports', '--operator', 'bad operator name')).code, 2);
  assert.equal((await mod(ctx, 'frobnicate', '--operator', op)).code, 2);
  assert.equal((await auditRows(ctx)).length, before);

  // There is no HTTP moderation surface.
  for (const path of ['/v1/admin/reports', '/v1/moderation/reports', '/v1/admin/users']) {
    expectError(await ctx.api.get(path, a!.token), 404, 'NOT_FOUND');
  }
});

test('account deletion anonymises the name, keeps ballots with a null voter and keeps the result', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cyd');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!, c!]);
  await toVoting(ctx, a!, round);
  const sa = round.subs.get(a!.id).id as string;
  const sb = round.subs.get(b!.id).id as string;
  const sc = round.subs.get(c!.id);
  expectStatus(await vote(ctx.api, b!, round.challengeId, sa), 201);
  expectStatus(await vote(ctx.api, c!, round.challengeId, sa), 201);
  expectStatus(await vote(ctx.api, a!, round.challengeId, sb), 201);
  const done = await toCompleted(ctx, a!, round);
  assert.equal(done.result.outcome, 'WINNER');
  assert.deepEqual(done.result.coWinnerSubmissionIds, [sa]);
  const storedResult = (await ctx.pool.query('SELECT * FROM challenge_results WHERE challenge_id = $1', [round.challengeId])).rows[0];
  const historyBefore = (await ctx.api.get('/v1/me/history', a!.token)).json.items[0];

  // The body must confirm the deletion.
  expectError(await ctx.api.del('/v1/me', c!.token, {}), 400, 'VALIDATION');
  expectError(await ctx.api.del('/v1/me', c!.token, { confirm: 'delete' }), 400, 'VALIDATION');
  expectStatus(await ctx.api.get('/v1/me', c!.token), 200);

  expectStatus(await ctx.api.del('/v1/me', c!.token, { confirm: 'DELETE' }), 204);
  expectError(await ctx.api.del('/v1/me', c!.token, { confirm: 'DELETE' }), 401, 'UNAUTHENTICATED');
  expectError(await ctx.api.get('/v1/me', c!.token), 401, 'UNAUTHENTICATED');

  const user = (await ctx.pool.query('SELECT display_name, deleted_at FROM users WHERE id = $1', [c!.id])).rows[0];
  assert.equal(user.display_name, null);
  assert.ok(user.deleted_at instanceof Date);
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM sessions WHERE user_id = $1', [c!.id])).rows[0].n, 0);
  assert.equal((await ctx.pool.query('SELECT count(*)::int AS n FROM recovery_codes WHERE user_id = $1', [c!.id])).rows[0].n, 0);

  // Ballots: all three kept; the deleted voter's row now has a null voter and the same target.
  const votes = (await ctx.pool.query('SELECT voter_id, submission_id FROM votes WHERE challenge_id = $1', [round.challengeId])).rows;
  assert.equal(votes.length, 3);
  assert.equal(votes.filter((v) => v.voter_id === c!.id).length, 0);
  const anon = votes.filter((v) => v.voter_id === null);
  assert.equal(anon.length, 1);
  assert.equal(anon[0].submission_id, sa);

  // The stored result is untouched and every remaining member still reads the same one.
  const afterResult = (await ctx.pool.query('SELECT * FROM challenge_results WHERE challenge_id = $1', [round.challengeId])).rows[0];
  assert.deepEqual(afterResult, storedResult);
  for (const p of [a!, b!]) {
    const v = await viewChallenge(ctx.api, p, round.challengeId);
    assert.deepEqual(v.result, done.result);
    const entry = v.roster.find((r: any) => r.userId === c!.id);
    assert.equal(entry?.displayName, 'Deleted player');
    assert.ok(!JSON.stringify(v).includes('Cyd'), 'the old display name appears nowhere');
  }
  const historyAfter = (await ctx.api.get('/v1/me/history', a!.token)).json.items[0];
  assert.deepEqual(historyAfter.result, historyBefore.result);

  // The deleted user's photo is gone and their submission is anonymous and REMOVED.
  const subRow = (await ctx.pool.query('SELECT author_id, status FROM submissions WHERE id = $1', [sc.id])).rows[0];
  assert.deepEqual(subRow, { author_id: null, status: 'REMOVED' });
  for (const id of [sc.fullMediaId, sc.thumbnailMediaId]) {
    const m = await storedMedia(ctx, id);
    assert.equal(m.purged, true);
    assert.equal(m.bytes, null);
    expectError(await ctx.api.get(`/v1/media/${id}`, a!.token), 404, 'NOT_FOUND');
  }
  const room = await ctx.api.get(`/v1/rooms/${round.roomId}`, a!.token);
  assert.ok(!room.json.members.some((m: any) => m.userId === c!.id));
  const audit = await auditRows(ctx, 'ACCOUNT_DELETED');
  assert.ok(audit.some((r) => r.target_id === c!.id && r.actor === `user:${c!.id}`));
});

test('deleting an account that owns a room deletes the room for everyone', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!, b!]);
  expectStatus(await ctx.api.del('/v1/me', a!.token, { confirm: 'DELETE' }), 204);
  expectError(await ctx.api.get(`/v1/rooms/${round.roomId}`, b!.token), 404, 'NOT_FOUND');
  expectError(await ctx.api.get(`/v1/challenges/${round.challengeId}`, b!.token), 404, 'NOT_FOUND');
  const ch = (await ctx.pool.query('SELECT state FROM challenges WHERE id = $1', [round.challengeId])).rows[0];
  assert.equal(ch.state, 'CANCELLED');
  const m = await storedMedia(ctx, round.subs.get(b!.id).fullMediaId);
  assert.equal(m.purged, true);
  assert.equal(m.bytes, null);
});
