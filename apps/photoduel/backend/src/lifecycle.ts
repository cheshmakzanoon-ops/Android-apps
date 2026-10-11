import type { AppDeps } from './deps.js';
import { DAY_MS } from './lib/clock.js';
import { ms, ts, withTx, type Queryable, type Tx } from './lib/db.js';
import type { ObjectStore } from './lib/objectStore.js';
import { tally, type Outcome, type SubmissionStatus, type TallyResult } from './lib/tally.js';

export type ChallengeState = 'CREATED' | 'OPEN_FOR_SUBMISSIONS' | 'VOTING' | 'COMPLETED' | 'ARCHIVED' | 'CANCELLED';

export interface ChallengeRow {
  id: string;
  room_id: string;
  round_number: number;
  prompt_id: string;
  prompt_title: string;
  prompt_brief: string;
  state: ChallengeState;
  outcome: Outcome | null;
  submission_hours: number;
  voting_hours: number;
  created_at: Date;
  started_at: Date | null;
  submission_deadline: Date | null;
  voting_deadline: Date | null;
  completed_at: Date | null;
  archived_at: Date | null;
  cancelled_at: Date | null;
  media_purged_at: Date | null;
}

export const ARCHIVE_AFTER_MS = 30 * DAY_MS;
export const LIVE_STATES: readonly ChallengeState[] = ['CREATED', 'OPEN_FOR_SUBMISSIONS', 'VOTING'];

/** Locks the challenge row (SELECT ... FOR UPDATE). Every rule decision happens under this lock. */
export async function lockChallenge(tx: Tx, challengeId: string): Promise<ChallengeRow | null> {
  const { rows } = await tx.query<ChallengeRow>('SELECT * FROM challenges WHERE id = $1 FOR UPDATE', [challengeId]);
  return rows[0] ?? null;
}

export function transitionDue(c: ChallengeRow, now: number): boolean {
  if (c.state === 'OPEN_FOR_SUBMISSIONS') return now >= (ms(c.submission_deadline) ?? Infinity);
  if (c.state === 'VOTING') return now >= (ms(c.voting_deadline) ?? Infinity);
  if (c.state === 'COMPLETED') return now >= (ms(c.completed_at) ?? Infinity) + ARCHIVE_AFTER_MS;
  return false;
}

/** Marks the media of a challenge as purged and returns the object keys to delete after commit. */
export async function purgeChallengeMedia(tx: Tx, challengeId: string, now: number): Promise<string[]> {
  const { rows } = await tx.query<{ storage_key: string }>(
    'UPDATE media SET purged_at = $2 WHERE challenge_id = $1 AND purged_at IS NULL RETURNING storage_key',
    [challengeId, ts(now)],
  );
  await tx.query('UPDATE challenges SET media_purged_at = $2 WHERE id = $1 AND media_purged_at IS NULL', [
    challengeId,
    ts(now),
  ]);
  return rows.map((r) => r.storage_key);
}

export async function purgeMediaIds(tx: Tx, mediaIds: string[], now: number): Promise<string[]> {
  if (mediaIds.length === 0) return [];
  const { rows } = await tx.query<{ storage_key: string }>(
    'UPDATE media SET purged_at = $2 WHERE id = ANY($1::uuid[]) AND purged_at IS NULL RETURNING storage_key',
    [mediaIds, ts(now)],
  );
  return rows.map((r) => r.storage_key);
}

/** Deletes objects after the database commit. A failure is left to the orphan sweeper. */
export async function deleteObjects(store: ObjectStore, keys: string[]): Promise<void> {
  await Promise.all(keys.map((k) => store.delete(k).catch(() => undefined)));
}

/** Gathers the tally inputs at close, computes, and writes challenge_results once (primary key). */
export async function computeAndStoreResult(tx: Tx, c: ChallengeRow, now: number): Promise<Outcome> {
  const roster = await tx.query<{ user_id: string }>('SELECT user_id FROM roster WHERE challenge_id = $1', [c.id]);
  const rosterIds = roster.rows.map((r) => r.user_id);
  const active = await tx.query<{ user_id: string }>(
    `SELECT m.user_id FROM room_members m JOIN users u ON u.id = m.user_id
      WHERE m.room_id = $1 AND m.left_at IS NULL AND u.deleted_at IS NULL AND m.user_id = ANY($2::uuid[])`,
    [c.room_id, rosterIds],
  );
  const subs = await tx.query<{ id: string; author_id: string | null; status: SubmissionStatus }>(
    'SELECT id, author_id, status FROM submissions WHERE challenge_id = $1',
    [c.id],
  );
  const blocks = await tx.query<{ blocker_id: string; blocked_id: string }>(
    'SELECT blocker_id, blocked_id FROM blocks WHERE blocker_id = ANY($1::uuid[]) AND blocked_id = ANY($1::uuid[])',
    [rosterIds],
  );
  const ballots = await tx.query<{ voter_id: string | null; submission_id: string }>(
    'SELECT voter_id, submission_id FROM votes WHERE challenge_id = $1 ORDER BY cast_at, id',
    [c.id],
  );
  const result: TallyResult = tally({
    roster: rosterIds,
    activeMembers: active.rows.map((r) => r.user_id),
    submissions: subs.rows.map((s) => ({ id: s.id, author: s.author_id, status: s.status })),
    blocks: blocks.rows.map((b) => [b.blocker_id, b.blocked_id] as const),
    ballots: ballots.rows.map((b) => ({ voter: b.voter_id, submission: b.submission_id })),
  });
  await tx.query(
    `INSERT INTO challenge_results
       (challenge_id, outcome, co_winner_submission_ids, tallies, ballot_count, eligible_voter_count, computed_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7)
     ON CONFLICT (challenge_id) DO NOTHING`,
    [
      c.id,
      result.outcome,
      JSON.stringify(result.coWinnerSubmissionIds),
      JSON.stringify(result.tallies),
      result.ballotCount,
      result.eligibleVoterCount,
      ts(now),
    ],
  );
  const stored = await tx.query<{ outcome: Outcome }>('SELECT outcome FROM challenge_results WHERE challenge_id = $1', [
    c.id,
  ]);
  return stored.rows[0]!.outcome;
}

/**
 * Applies every transition that server time makes due, on a row already locked FOR UPDATE.
 * Returns the updated row and the object keys to delete once the transaction commits.
 */
export async function advanceLocked(tx: Tx, row: ChallengeRow, now: number): Promise<{ row: ChallengeRow; purge: string[] }> {
  let c = row;
  const purge: string[] = [];
  for (let guard = 0; guard < 4 && transitionDue(c, now); guard++) {
    if (c.state === 'OPEN_FOR_SUBMISSIONS') {
      const deadline = ms(c.submission_deadline)!;
      const { rows } = await tx.query<{ n: string }>(
        `SELECT count(*) AS n FROM submissions s JOIN roster r ON r.challenge_id = s.challenge_id AND r.user_id = s.author_id
          WHERE s.challenge_id = $1 AND s.status = 'ACTIVE'`,
        [c.id],
      );
      if (Number(rows[0]?.n ?? 0) < 2) {
        const outcome = await computeAndStoreResult(tx, c, now);
        c = await update(tx, c.id, `state = 'COMPLETED', outcome = $2, completed_at = $3`, [outcome, ts(deadline)]);
      } else {
        c = await update(tx, c.id, `state = 'VOTING'`, []);
      }
    } else if (c.state === 'VOTING') {
      const deadline = ms(c.voting_deadline)!;
      const outcome = await computeAndStoreResult(tx, c, now);
      c = await update(tx, c.id, `state = 'COMPLETED', outcome = $2, completed_at = $3`, [outcome, ts(deadline)]);
    } else if (c.state === 'COMPLETED') {
      purge.push(...(await purgeChallengeMedia(tx, c.id, now)));
      c = await update(tx, c.id, `state = 'ARCHIVED', archived_at = $2`, [ts(now)]);
    }
  }
  return { row: c, purge };
}

async function update(tx: Tx, id: string, set: string, params: unknown[]): Promise<ChallengeRow> {
  const { rows } = await tx.query<ChallengeRow>(`UPDATE challenges SET ${set} WHERE id = $1 RETURNING *`, [id, ...params]);
  return rows[0]!;
}

/**
 * Lazy evaluation for read paths: if a transition is due, lock the row, advance it and commit.
 * Cheap when nothing is due (one indexed read, no lock).
 */
export async function ensureAdvanced(deps: AppDeps, challengeId: string): Promise<void> {
  const now = deps.clock.now();
  const { rows } = await deps.pool.query<ChallengeRow>('SELECT * FROM challenges WHERE id = $1', [challengeId]);
  const c = rows[0];
  if (!c || !transitionDue(c, now)) return;
  const purge = await withTx(deps.pool, async (tx) => {
    const locked = await lockChallenge(tx, challengeId);
    if (!locked) return [];
    return (await advanceLocked(tx, locked, deps.clock.now())).purge;
  });
  await deleteObjects(deps.store, purge);
}

/** Advances every challenge of a room that is due (used by room listings and history). */
export async function ensureRoomAdvanced(deps: AppDeps, q: Queryable, roomIds: string[]): Promise<void> {
  if (roomIds.length === 0) return;
  const now = deps.clock.now();
  const { rows } = await q.query<{ id: string }>(
    `SELECT id FROM challenges WHERE room_id = ANY($1::uuid[]) AND (
        (state = 'OPEN_FOR_SUBMISSIONS' AND submission_deadline <= $2)
     OR (state = 'VOTING' AND voting_deadline <= $2)
     OR (state = 'COMPLETED' AND completed_at <= $3))`,
    [roomIds, ts(now), ts(now - ARCHIVE_AFTER_MS)],
  );
  for (const r of rows) await ensureAdvanced(deps, r.id);
}

