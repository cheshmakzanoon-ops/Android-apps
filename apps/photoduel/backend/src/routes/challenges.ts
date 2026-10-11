import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { activeMembership } from '../access.js';
import { callerOf } from '../auth.js';
import type { AppDeps } from '../deps.js';
import {
  advanceLocked,
  deleteObjects,
  ensureAdvanced,
  ensureRoomAdvanced,
  lockChallenge,
  purgeChallengeMedia,
  type ChallengeRow,
} from '../lifecycle.js';
import { HOUR_MS } from '../lib/clock.js';
import { ts, withTx, type Queryable, type Tx } from '../lib/db.js';
import { ApiError, notFound } from '../lib/errors.js';
import { challengeJson, challengeViewJson, decodeCursor, encodeCursor, resultsFor } from '../views.js';
import { EmptyBody, pageLimit, parseBody, pathId, strictQuery } from '../validation.js';

const CreateChallengeBody = z.strictObject({
  promptId: z.string().min(1).max(64),
  submissionHours: z.union([z.literal(1), z.literal(6), z.literal(24), z.literal(48), z.literal(72)]),
  votingHours: z.union([z.literal(1), z.literal(6), z.literal(24)]),
});

/** Challenge plus room facts, visible only to active members of a room that is not deleted. */
export async function challengeForMember(q: Queryable, challengeId: string, userId: string) {
  const { rows } = await q.query<ChallengeRow & { owner_id: string; room_name: string }>(
    `SELECT c.*, r.owner_id, r.name AS room_name FROM challenges c JOIN rooms r ON r.id = c.room_id
      WHERE c.id = $1 AND r.deleted_at IS NULL`,
    [challengeId],
  );
  const c = rows[0];
  if (!c) return null;
  const m = await activeMembership(q, c.room_id, userId);
  if (!m) return null;
  return { challenge: c, membership: m };
}

/** Locks room then challenge (the global lock order), after checking the caller is the owner. */
async function lockAsOwner(tx: Tx, challengeId: string, userId: string, now: number) {
  const pre = await challengeForMember(tx, challengeId, userId);
  if (!pre) throw notFound();
  const room = await tx.query<{ owner_id: string; deleted_at: Date | null }>(
    'SELECT owner_id, deleted_at FROM rooms WHERE id = $1 FOR UPDATE',
    [pre.challenge.room_id],
  );
  if (!room.rows[0] || room.rows[0].deleted_at !== null) throw notFound();
  if (room.rows[0].owner_id !== userId) throw new ApiError('FORBIDDEN', 'Only the room owner can do this');
  const locked = await lockChallenge(tx, challengeId);
  if (!locked) throw notFound();
  const advanced = await advanceLocked(tx, locked, now);
  return advanced;
}

export function challengeRoutes(app: FastifyInstance, deps: AppDeps): void {
  app.post('/v1/rooms/:roomId/challenges', async (req, reply) => {
    const caller = callerOf(req);
    const roomId = pathId((req.params as { roomId: string }).roomId);
    const body = parseBody(CreateChallengeBody, req.body);
    const prompt = deps.prompts.byId.get(body.promptId);
    const now = deps.clock.now();
    const { row, purge } = await withTx(deps.pool, async (tx) => {
      const room = await tx.query<{ owner_id: string }>(
        'SELECT owner_id FROM rooms WHERE id = $1 AND deleted_at IS NULL FOR UPDATE',
        [roomId],
      );
      if (!room.rows[0] || !(await activeMembership(tx, roomId, caller.userId))) throw notFound();
      if (room.rows[0].owner_id !== caller.userId) throw new ApiError('FORBIDDEN', 'Only the room owner can create a challenge');
      if (!prompt) throw new ApiError('VALIDATION', 'Invalid field promptId: unknown prompt');
      // Apply due transitions first, so a window that has closed does not block a rematch.
      const live = await tx.query<{ id: string }>(
        `SELECT id FROM challenges WHERE room_id = $1 AND state IN ('CREATED', 'OPEN_FOR_SUBMISSIONS', 'VOTING')`,
        [roomId],
      );
      const keys: string[] = [];
      for (const l of live.rows) {
        const locked = (await lockChallenge(tx, l.id))!;
        const adv = await advanceLocked(tx, locked, now);
        keys.push(...adv.purge);
        if (adv.row.state === 'CREATED' || adv.row.state === 'OPEN_FOR_SUBMISSIONS' || adv.row.state === 'VOTING') {
          throw new ApiError('CONFLICT', 'This room already has an active challenge');
        }
      }
      const round = await tx.query<{ n: number }>(
        'SELECT COALESCE(max(round_number), 0) + 1 AS n FROM challenges WHERE room_id = $1',
        [roomId],
      );
      const id = randomUUID();
      const inserted = await tx.query<ChallengeRow>(
        `INSERT INTO challenges (id, room_id, round_number, prompt_id, prompt_title, prompt_brief, state,
                                 submission_hours, voting_hours, created_by, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, 'CREATED', $7, $8, $9, $10) RETURNING *`,
        [id, roomId, round.rows[0]!.n, prompt.id, prompt.title, prompt.brief, body.submissionHours, body.votingHours, caller.userId, ts(now)],
      );
      return { row: inserted.rows[0]!, purge: keys };
    });
    await deleteObjects(deps.store, purge);
    reply.code(201);
    return challengeJson(row, now);
  });

  app.post('/v1/challenges/:challengeId/start', async (req) => {
    const caller = callerOf(req);
    const challengeId = pathId((req.params as { challengeId: string }).challengeId);
    parseBody(EmptyBody, req.body);
    const now = deps.clock.now();
    const row = await withTx(deps.pool, async (tx) => {
      const { row: c } = await lockAsOwner(tx, challengeId, caller.userId, now);
      if (c.state !== 'CREATED') throw new ApiError('INVALID_TRANSITION', `Cannot start a challenge in state ${c.state}`);
      const members = await tx.query<{ user_id: string }>(
        `SELECT m.user_id FROM room_members m JOIN users u ON u.id = m.user_id
          WHERE m.room_id = $1 AND m.left_at IS NULL AND u.deleted_at IS NULL`,
        [c.room_id],
      );
      if (members.rows.length < deps.config.limits.minMembersToStart) {
        throw new ApiError('CONFLICT', `At least ${deps.config.limits.minMembersToStart} members are needed`);
      }
      const submissionDeadline = now + c.submission_hours * HOUR_MS;
      const votingDeadline = submissionDeadline + c.voting_hours * HOUR_MS;
      await tx.query('INSERT INTO roster (challenge_id, user_id) SELECT $1, unnest($2::uuid[])', [
        c.id,
        members.rows.map((m) => m.user_id),
      ]);
      const updated = await tx.query<ChallengeRow>(
        `UPDATE challenges SET state = 'OPEN_FOR_SUBMISSIONS', started_at = $2, submission_deadline = $3, voting_deadline = $4
          WHERE id = $1 RETURNING *`,
        [c.id, ts(now), ts(submissionDeadline), ts(votingDeadline)],
      );
      return updated.rows[0]!;
    });
    return challengeJson(row, now);
  });

  app.post('/v1/challenges/:challengeId/cancel', async (req) => {
    const caller = callerOf(req);
    const challengeId = pathId((req.params as { challengeId: string }).challengeId);
    parseBody(EmptyBody, req.body);
    const now = deps.clock.now();
    const { row, purge } = await withTx(deps.pool, async (tx) => {
      const adv = await lockAsOwner(tx, challengeId, caller.userId, now);
      const c = adv.row;
      if (c.state !== 'CREATED' && c.state !== 'OPEN_FOR_SUBMISSIONS') {
        throw new ApiError('INVALID_TRANSITION', `Cannot cancel a challenge in state ${c.state}`);
      }
      const updated = await tx.query<ChallengeRow>(
        `UPDATE challenges SET state = 'CANCELLED', cancelled_at = $2 WHERE id = $1 RETURNING *`,
        [c.id, ts(now)],
      );
      const keys = await purgeChallengeMedia(tx, c.id, now);
      return { row: updated.rows[0]!, purge: [...adv.purge, ...keys] };
    });
    await deleteObjects(deps.store, purge);
    return challengeJson(row, now);
  });

  app.get('/v1/challenges/:challengeId', async (req) => {
    const caller = callerOf(req);
    const challengeId = pathId((req.params as { challengeId: string }).challengeId);
    const pre = await challengeForMember(deps.pool, challengeId, caller.userId);
    if (!pre) throw notFound();
    await ensureAdvanced(deps, challengeId);
    const found = await challengeForMember(deps.pool, challengeId, caller.userId);
    if (!found) throw notFound();
    return challengeViewJson(deps.pool, found.challenge, caller.userId, found.challenge.owner_id, deps.clock.now());
  });

  app.get('/v1/rooms/:roomId/challenges', async (req) => {
    const caller = callerOf(req);
    const roomId = pathId((req.params as { roomId: string }).roomId);
    const q = strictQuery(req.query, ['cursor', 'limit']);
    const limit = pageLimit(q.limit);
    const cursor = decodeCursor(q.cursor);
    if (q.cursor !== undefined && q.cursor !== '' && (!cursor || typeof cursor[0] !== 'number')) {
      throw new ApiError('VALIDATION', 'Invalid field cursor');
    }
    if (!(await activeMembership(deps.pool, roomId, caller.userId))) throw notFound();
    await ensureRoomAdvanced(deps, deps.pool, [roomId]);
    const before = cursor ? (cursor[0] as number) : 2 ** 31 - 1;
    const { rows } = await deps.pool.query<ChallengeRow>(
      `SELECT * FROM challenges WHERE room_id = $1 AND round_number < $2 ORDER BY round_number DESC LIMIT $3`,
      [roomId, before, limit + 1],
    );
    const now = deps.clock.now();
    const page = rows.slice(0, limit);
    return {
      items: page.map((c) => challengeJson(c, now)),
      nextCursor: rows.length > limit ? encodeCursor([page[page.length - 1]!.round_number]) : null,
    };
  });

  app.get('/v1/me/history', async (req) => {
    const caller = callerOf(req);
    const q = strictQuery(req.query, ['cursor', 'limit']);
    const limit = pageLimit(q.limit);
    const cursor = decodeCursor(q.cursor);
    if (
      q.cursor !== undefined &&
      q.cursor !== '' &&
      (!cursor || typeof cursor[0] !== 'number' || typeof cursor[1] !== 'string')
    ) {
      throw new ApiError('VALIDATION', 'Invalid field cursor');
    }
    const rooms = await deps.pool.query<{ room_id: string }>(
      `SELECT m.room_id FROM room_members m JOIN rooms r ON r.id = m.room_id
        WHERE m.user_id = $1 AND m.left_at IS NULL AND r.deleted_at IS NULL`,
      [caller.userId],
    );
    await ensureRoomAdvanced(deps, deps.pool, rooms.rows.map((r) => r.room_id));
    const beforeTs = cursor ? new Date(cursor[0] as number) : new Date(8.64e15);
    const beforeId = cursor ? (cursor[1] as string) : 'ffffffff-ffff-ffff-ffff-ffffffffffff';
    // Rooms the caller currently belongs to; within them, challenges the caller was on the
    // roster for or that were created after the caller joined. Rooms the caller left are excluded.
    const { rows } = await deps.pool.query<ChallengeRow & { room_name: string }>(
      `SELECT c.*, r.name AS room_name FROM challenges c
         JOIN rooms r ON r.id = c.room_id AND r.deleted_at IS NULL
         JOIN room_members m ON m.room_id = c.room_id AND m.user_id = $1 AND m.left_at IS NULL
        WHERE (EXISTS (SELECT 1 FROM roster x WHERE x.challenge_id = c.id AND x.user_id = $1) OR c.created_at >= m.joined_at)
          AND (c.created_at, c.id) < ($2, $3::uuid)
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT $4`,
      [caller.userId, beforeTs, beforeId, limit + 1],
    );
    const page = rows.slice(0, limit);
    const ids = page.map((c) => c.id);
    const results = await resultsFor(deps.pool, ids);
    const mine = await deps.pool.query<{ challenge_id: string; id: string }>(
      `SELECT challenge_id, id FROM submissions WHERE author_id = $1 AND challenge_id = ANY($2::uuid[])
          AND status IN ('ACTIVE', 'HIDDEN')`,
      [caller.userId, ids],
    );
    const mineBy = new Map(mine.rows.map((m) => [m.challenge_id, m.id]));
    const now = deps.clock.now();
    const last = page[page.length - 1];
    return {
      items: page.map((c) => ({
        challenge: challengeJson(c, now),
        roomName: c.room_name,
        mySubmissionId: mineBy.get(c.id) ?? null,
        result: c.state === 'COMPLETED' || c.state === 'ARCHIVED' ? results.get(c.id) ?? null : null,
      })),
      nextCursor: rows.length > limit && last ? encodeCursor([last.created_at.getTime(), last.id]) : null,
    };
  });
}
