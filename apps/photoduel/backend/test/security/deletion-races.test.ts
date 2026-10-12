import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import pg from 'pg';
import { createTestContext, submitPhoto, testDatabaseUrl, type Res, type TestContext } from '../support/harness.js';
import { expectStatus, listFiles, openRound, players, toVoting, viewChallenge, vote } from '../support/scenario.js';

/**
 * Account deletion racing an in-flight write by the same account. A separate connection holds a
 * lock that parks the write inside its transaction (after its membership checks), the deletion is
 * then sent, and the lock is released. Whatever order the database settles on, the deletion
 * guarantees of api-contract.md section 2 must hold afterwards: no live photo, no ballot carrying
 * the voter's id, no active membership for the deleted account.
 */

let ctx: TestContext;
before(async () => {
  ctx = await createTestContext();
});
after(async () => {
  await ctx?.close();
});

async function gate(sql: string, params: unknown[] = []): Promise<{ pid: number; release: () => Promise<void> }> {
  const c = new pg.Client({ connectionString: testDatabaseUrl(), options: `-c search_path=${ctx.schema}` });
  await c.connect();
  await c.query('BEGIN');
  await c.query(sql, params);
  const pid = (await c.query<{ pid: number }>('SELECT pg_backend_pid() AS pid')).rows[0]!.pid;
  return {
    pid,
    release: async () => {
      await c.query('COMMIT');
      await c.end();
    },
  };
}

async function waitFor<T>(probe: () => Promise<T | null>, label: string, timeoutMs = 10_000): Promise<T> {
  const start = Date.now();
  for (;;) {
    const v = await probe();
    if (v !== null) return v;
    if (Date.now() - start > timeoutMs) throw new Error(`timed out waiting for ${label}`);
    await new Promise((r) => setTimeout(r, 20));
  }
}

/** A backend (other than the excluded ones) that is waiting on a lock held by one of `holders`. */
async function blockedBy(holders: number[], exclude: number[] = []): Promise<number | null> {
  const { rows } = await ctx.pool.query<{ pid: number }>(
    `SELECT pid FROM pg_stat_activity WHERE pg_blocking_pids(pid) && $1::int[] AND NOT (pid = ANY($2::int[])) LIMIT 1`,
    [holders, exclude],
  );
  return rows[0]?.pid ?? null;
}

/**
 * Parks `write` behind the gate, sends the deletion, waits until the deletion has either finished
 * or is itself waiting, then opens the gate. Returns both responses.
 */
async function race(
  gateSql: string,
  gateParams: unknown[],
  write: () => Promise<Res>,
  deletion: () => Promise<Res>,
): Promise<{ write: Res; deletion: Res }> {
  const g = await gate(gateSql, gateParams);
  try {
    const w = write();
    const writer = await waitFor(() => blockedBy([g.pid]), 'the write to reach the gate');
    let deletionDone = false;
    const d = deletion().finally(() => {
      deletionDone = true;
    });
    await waitFor(async () => (deletionDone ? true : await blockedBy([g.pid, writer], [writer])), 'the deletion to finish or wait');
    await g.release();
    return { write: await w, deletion: await d };
  } catch (e) {
    await g.release().catch(() => undefined);
    throw e;
  }
}

test('a photo upload racing account deletion never leaves a live photo of the deleted account', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!, c!]);
  const before = listFiles(ctx.mediaRoot);

  // The upload's quota row is the last write before its media rows: hold the table there.
  const r = await race(
    'LOCK TABLE rate_events IN SHARE MODE',
    [],
    () => submitPhoto(ctx.api, b!, round.challengeId, '#abcdef'),
    () => ctx.api.del('/v1/me', b!.token, { confirm: 'DELETE' }),
  );
  expectStatus(r.deletion, 204);
  assert.ok([201, 401].includes(r.write.status), `upload: ${r.write.status} ${JSON.stringify(r.write.json)}`);

  const live = await ctx.pool.query(
    `SELECT s.id, s.status, s.author_id FROM submissions s
      WHERE s.challenge_id = $1 AND s.status IN ('ACTIVE', 'HIDDEN') AND (s.author_id IS NULL OR s.author_id = $2)`,
    [round.challengeId, b!.id],
  );
  assert.deepEqual(live.rows, [], 'no live submission for the deleted account');
  const media = await ctx.pool.query(
    `SELECT id FROM media WHERE challenge_id = $1 AND purged_at IS NULL AND (owner_id IS NULL OR owner_id = $2)`,
    [round.challengeId, b!.id],
  );
  assert.deepEqual(media.rows, [], 'every photo of the deleted account is purged');
  assert.deepEqual(listFiles(ctx.mediaRoot), before, 'no object of the deleted account remains on disk');

  await toVoting(ctx, a!, round);
  const v = await viewChallenge(ctx.api, c!, round.challengeId);
  assert.deepEqual(v.submissions.map((s: any) => s.authorUserId).sort(), [a!.id, c!.id].sort());
});

test('a ballot racing account deletion is kept without the voter id', async () => {
  const [a, b, c] = await players(ctx.api, 'Ada', 'Ben', 'Cy');
  const round = await openRound(ctx, a!, [b!, c!], [a!, c!]);
  await toVoting(ctx, a!, round);
  const r = await race(
    'LOCK TABLE votes IN SHARE MODE',
    [],
    () => vote(ctx.api, b!, round.challengeId, round.subs.get(a!.id).id),
    () => ctx.api.del('/v1/me', b!.token, { confirm: 'DELETE' }),
  );
  expectStatus(r.deletion, 204);
  assert.ok([201, 401].includes(r.write.status), `ballot: ${r.write.status} ${JSON.stringify(r.write.json)}`);
  const named = await ctx.pool.query('SELECT count(*)::int AS n FROM votes WHERE voter_id = $1', [b!.id]);
  assert.equal(named.rows[0].n, 0, 'no ballot keeps the deleted voter id');
});

test('a join racing account deletion never leaves the deleted account as an active member', async () => {
  const [a, j] = await players(ctx.api, 'Ada', 'Joy');
  const room = await ctx.api.post('/v1/rooms', a!.token, { name: 'Race room' });
  expectStatus(room, 201);
  const roomId = room.json.id as string;
  const inv = await ctx.api.post(`/v1/rooms/${roomId}/invitations`, a!.token, {});
  expectStatus(inv, 201);

  // The join locks the room row before it inserts the membership: hold the room there.
  const r = await race(
    'SELECT 1 FROM rooms WHERE id = $1 FOR UPDATE',
    [roomId],
    () => ctx.api.post(`/v1/invitations/${inv.json.token}/join`, j!.token),
    () => ctx.api.del('/v1/me', j!.token, { confirm: 'DELETE' }),
  );
  expectStatus(r.deletion, 204);
  assert.ok([200, 401, 404].includes(r.write.status), `join: ${r.write.status} ${JSON.stringify(r.write.json)}`);

  const active = await ctx.pool.query('SELECT count(*)::int AS n FROM room_members WHERE user_id = $1 AND left_at IS NULL', [j!.id]);
  assert.equal(active.rows[0].n, 0, 'the deleted account holds no seat');
  const detail = await ctx.api.get(`/v1/rooms/${roomId}`, a!.token);
  assert.deepEqual(detail.json.members.map((m: any) => m.userId), [a!.id]);
});
