import { randomUUID } from 'node:crypto';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { activeMembership, blockedEitherWay } from '../access.js';
import { callerOf } from '../auth.js';
import type { AppDeps } from '../deps.js';
import { advanceLocked, deleteObjects, ensureAdvanced, lockChallenge, purgeMediaIds, type ChallengeRow } from '../lifecycle.js';
import { DAY_MS } from '../lib/clock.js';
import { ms, ts, withTx, type Tx } from '../lib/db.js';
import { ApiError, notFound } from '../lib/errors.js';
import { normalizePhoto, type EncodedImage } from '../lib/images.js';
import { assertQuota, consumeQuota } from '../lib/rateLimit.js';
import { challengeForMember } from './challenges.js';
import { submissionById } from '../views.js';
import { parseBody, pathId, Uuid } from '../validation.js';

const BallotBody = z.strictObject({ submissionId: Uuid });

const tooLarge = () => new ApiError('PAYLOAD_TOO_LARGE', 'The upload is larger than 3 MB');

/** Reads exactly the two expected file parts, enforcing the total body limit while streaming. */
async function readUpload(req: FastifyRequest, limit: number): Promise<{ full: Buffer; thumbnail: Buffer }> {
  const declared = Number(req.headers['content-length'] ?? 0);
  if (Number.isFinite(declared) && declared > limit) throw tooLarge();
  if (!req.isMultipart()) throw new ApiError('VALIDATION', 'Expected multipart/form-data with full and thumbnail parts');
  const got: Record<string, Buffer> = {};
  let total = 0;
  for await (const part of req.parts()) {
    if (part.type !== 'file') throw new ApiError('VALIDATION', `Unknown field: ${part.fieldname}`);
    if (part.fieldname !== 'full' && part.fieldname !== 'thumbnail') {
      throw new ApiError('VALIDATION', `Unknown field: ${part.fieldname}`);
    }
    if (got[part.fieldname]) throw new ApiError('VALIDATION', `Duplicate field: ${part.fieldname}`);
    const chunks: Buffer[] = [];
    for await (const chunk of part.file) {
      total += (chunk as Buffer).length;
      if (total > limit) throw tooLarge();
      chunks.push(chunk as Buffer);
    }
    if (part.file.truncated) throw tooLarge();
    got[part.fieldname] = Buffer.concat(chunks);
  }
  if (!got.full) throw new ApiError('VALIDATION', 'Invalid field full: required');
  if (!got.thumbnail) throw new ApiError('VALIDATION', 'Invalid field thumbnail: required');
  return { full: got.full, thumbnail: got.thumbnail };
}

/** Checks that the caller may submit right now, on a locked and advanced challenge row. */
async function assertCanSubmit(tx: Tx, c: ChallengeRow, userId: string, now: number): Promise<void> {
  if (!(await activeMembership(tx, c.room_id, userId))) throw notFound();
  const roster = await tx.query('SELECT 1 FROM roster WHERE challenge_id = $1 AND user_id = $2', [c.id, userId]);
  if (c.state === 'CREATED' || c.state === 'CANCELLED') {
    throw new ApiError('INVALID_TRANSITION', `Submissions are not open in state ${c.state}`);
  }
  if (roster.rowCount === 0) throw new ApiError('FORBIDDEN', 'You are not on the roster of this challenge');
  const deadline = ms(c.submission_deadline);
  if (c.state !== 'OPEN_FOR_SUBMISSIONS' || deadline === null || now >= deadline) {
    throw new ApiError('DEADLINE_PASSED', 'The submission deadline has passed');
  }
}

export function submissionRoutes(app: FastifyInstance, deps: AppDeps): void {
  const limits = deps.config.limits;

  app.put('/v1/challenges/:challengeId/submission', async (req, reply) => {
    const caller = callerOf(req);
    const challengeId = pathId((req.params as { challengeId: string }).challengeId);

    // Cheap pre-checks so outsiders cannot make the server decode images. The authoritative
    // checks run again under the row lock after the body has been read.
    const pre = await challengeForMember(deps.pool, challengeId, caller.userId);
    if (!pre) throw notFound();
    const uploadRule = {
      bucket: 'upload',
      subject: caller.userId,
      windowMs: DAY_MS,
      maxCount: limits.uploadsPerUserPerDay,
      maxAmount: limits.uploadBytesPerUserPerDay,
    };
    await withTx(deps.pool, (tx) => assertQuota(tx, uploadRule, deps.clock.now()));

    const body = await readUpload(req, limits.uploadBodyLimitBytes);
    const photo = await normalizePhoto(body.full, body.thumbnail, limits);
    const uploadedBytes = body.full.length + body.thumbnail.length;

    const fullId = randomUUID();
    const thumbId = randomUUID();
    const fullKey = deps.store.newKey();
    const thumbKey = deps.store.newKey();
    await deps.store.put(fullKey, photo.full.bytes);
    await deps.store.put(thumbKey, photo.thumbnail.bytes);

    let outcome: { submissionId: string; created: boolean; purge: string[] };
    try {
      outcome = await withTx(deps.pool, async (tx) => {
        const now = deps.clock.now();
        const locked = await lockChallenge(tx, challengeId);
        if (!locked) throw notFound();
        const { row: c, purge } = await advanceLocked(tx, locked, now);
        await assertCanSubmit(tx, c, caller.userId, now);
        await consumeQuota(tx, uploadRule, now, uploadedBytes);

        const insertMedia = (id: string, key: string, variant: string, img: EncodedImage) =>
          tx.query(
            `INSERT INTO media (id, owner_id, challenge_id, variant, storage_key, sha256, mime, width, height, byte_size, created_at)
             VALUES ($1, $2, $3, $4, $5, $6, 'image/jpeg', $7, $8, $9, $10)`,
            [id, caller.userId, c.id, variant, key, img.sha256, img.width, img.height, img.bytes.length, ts(now)],
          );
        await insertMedia(fullId, fullKey, 'FULL', photo.full);
        await insertMedia(thumbId, thumbKey, 'THUMBNAIL', photo.thumbnail);

        const existing = await tx.query<{ id: string; full_media_id: string; thumb_media_id: string }>(
          `SELECT id, full_media_id, thumb_media_id FROM submissions
            WHERE challenge_id = $1 AND author_id = $2 AND status IN ('ACTIVE', 'HIDDEN') FOR UPDATE`,
          [c.id, caller.userId],
        );
        const prev = existing.rows[0];
        if (prev) {
          await tx.query(
            `UPDATE submissions SET full_media_id = $2, thumb_media_id = $3, width = $4, height = $5, updated_at = $6,
                    status = 'ACTIVE', hidden_at = NULL, hidden_reason = NULL
              WHERE id = $1`,
            [prev.id, fullId, thumbId, photo.full.width, photo.full.height, ts(now)],
          );
          const old = await purgeMediaIds(tx, [prev.full_media_id, prev.thumb_media_id], now);
          return { submissionId: prev.id, created: false, purge: [...purge, ...old] };
        }
        const id = randomUUID();
        await tx.query(
          `INSERT INTO submissions (id, challenge_id, author_id, status, full_media_id, thumb_media_id, width, height, created_at, updated_at)
           VALUES ($1, $2, $3, 'ACTIVE', $4, $5, $6, $7, $8, $8)`,
          [id, c.id, caller.userId, fullId, thumbId, photo.full.width, photo.full.height, ts(now)],
        );
        return { submissionId: id, created: true, purge };
      });
    } catch (err) {
      // Nothing references the new objects: remove them now rather than waiting for the sweeper.
      await deleteObjects(deps.store, [fullKey, thumbKey]);
      throw err;
    }
    // Old media of a replaced submission is deleted only after the new row has committed.
    await deleteObjects(deps.store, outcome.purge);
    reply.code(outcome.created ? 201 : 200);
    return submissionById(deps.pool, outcome.submissionId, caller.userId);
  });

  app.delete('/v1/challenges/:challengeId/submission', async (req, reply) => {
    const caller = callerOf(req);
    const challengeId = pathId((req.params as { challengeId: string }).challengeId);
    const pre = await challengeForMember(deps.pool, challengeId, caller.userId);
    if (!pre) throw notFound();
    const purge = await withTx(deps.pool, async (tx) => {
      const now = deps.clock.now();
      const locked = await lockChallenge(tx, challengeId);
      if (!locked) throw notFound();
      const { row: c, purge: due } = await advanceLocked(tx, locked, now);
      if (!(await activeMembership(tx, c.room_id, caller.userId))) throw notFound();
      const roster = await tx.query('SELECT 1 FROM roster WHERE challenge_id = $1 AND user_id = $2', [c.id, caller.userId]);
      if (roster.rowCount === 0 && c.state !== 'CREATED') {
        throw new ApiError('FORBIDDEN', 'You are not on the roster of this challenge');
      }
      if (c.state !== 'OPEN_FOR_SUBMISSIONS') {
        throw new ApiError('INVALID_TRANSITION', `Cannot withdraw in state ${c.state}`);
      }
      const { rows } = await tx.query<{ id: string; full_media_id: string; thumb_media_id: string }>(
        `UPDATE submissions SET status = 'WITHDRAWN', updated_at = $3
          WHERE challenge_id = $1 AND author_id = $2 AND status IN ('ACTIVE', 'HIDDEN')
          RETURNING id, full_media_id, thumb_media_id`,
        [c.id, caller.userId, ts(now)],
      );
      const s = rows[0];
      if (!s) throw notFound();
      return [...due, ...(await purgeMediaIds(tx, [s.full_media_id, s.thumb_media_id], now))];
    });
    await deleteObjects(deps.store, purge);
    reply.code(204);
  });

  app.delete('/v1/submissions/:submissionId', async (req, reply) => {
    const caller = callerOf(req);
    const submissionId = pathId((req.params as { submissionId: string }).submissionId);
    const found = await deps.pool.query<{ challenge_id: string; author_id: string | null }>(
      'SELECT challenge_id, author_id FROM submissions WHERE id = $1',
      [submissionId],
    );
    const s = found.rows[0];
    // Only the author learns that the submission exists.
    if (!s || s.author_id !== caller.userId) throw notFound();
    await ensureAdvanced(deps, s.challenge_id);
    const purge = await withTx(deps.pool, async (tx) => {
      const now = deps.clock.now();
      const locked = await lockChallenge(tx, s.challenge_id);
      if (!locked) throw notFound();
      const { row: c, purge: due } = await advanceLocked(tx, locked, now);
      if (c.state !== 'COMPLETED' && c.state !== 'ARCHIVED') {
        throw new ApiError('INVALID_TRANSITION', `Cannot delete a submission in state ${c.state}`);
      }
      const { rows } = await tx.query<{ full_media_id: string; thumb_media_id: string }>(
        `UPDATE submissions SET status = 'REMOVED', updated_at = $3
          WHERE id = $1 AND author_id = $2 RETURNING full_media_id, thumb_media_id`,
        [submissionId, caller.userId, ts(now)],
      );
      const r = rows[0];
      if (!r) throw notFound();
      return [...due, ...(await purgeMediaIds(tx, [r.full_media_id, r.thumb_media_id], now))];
    });
    await deleteObjects(deps.store, purge);
    reply.code(204);
  });

  // ---- Ballots -----------------------------------------------------------------------------

  app.post('/v1/challenges/:challengeId/ballot', async (req, reply) => {
    const caller = callerOf(req);
    const challengeId = pathId((req.params as { challengeId: string }).challengeId);
    const body = parseBody(BallotBody, req.body);
    const target = body.submissionId.toLowerCase();

    await withTx(deps.pool, (tx) =>
      consumeQuota(
        tx,
        { bucket: 'ballot', subject: caller.userId, windowMs: 60_000, maxCount: limits.ballotsPerUserPerMinute },
        deps.clock.now(),
      ),
    );

    const { status, ballot, purge } = await withTx(deps.pool, async (tx) => {
      const now = deps.clock.now();
      const locked = await lockChallenge(tx, challengeId);
      if (!locked) throw notFound();
      // Visibility first: non-members must not learn that the challenge exists.
      if (!(await activeMembership(tx, locked.room_id, caller.userId))) throw notFound();
      const { row: c, purge: due } = await advanceLocked(tx, locked, now);

      // 1. On the roster and still a member.
      const roster = await tx.query('SELECT 1 FROM roster WHERE challenge_id = $1 AND user_id = $2', [c.id, caller.userId]);
      if (roster.rowCount === 0) throw new ApiError('FORBIDDEN', 'You are not on the roster of this challenge');
      // 2 and 3. State and deadline, against server time.
      const votingDeadline = ms(c.voting_deadline);
      if (c.state !== 'VOTING') {
        if ((c.state === 'COMPLETED' || c.state === 'ARCHIVED') && votingDeadline !== null && now >= votingDeadline) {
          throw new ApiError('DEADLINE_PASSED', 'The voting deadline has passed');
        }
        throw new ApiError('INVALID_TRANSITION', `Voting is not open in state ${c.state}`);
      }
      if (votingDeadline === null || now >= votingDeadline) {
        throw new ApiError('DEADLINE_PASSED', 'The voting deadline has passed');
      }
      // 4. Target: ACTIVE, in this challenge, by a roster member, not the caller's own, not blocked.
      const t = await tx.query<{ author_id: string | null; status: string }>(
        `SELECT s.author_id, s.status FROM submissions s
          WHERE s.id = $1 AND s.challenge_id = $2
            AND EXISTS (SELECT 1 FROM roster r WHERE r.challenge_id = s.challenge_id AND r.user_id = s.author_id)`,
        [target, c.id],
      );
      const sub = t.rows[0];
      if (!sub || sub.status !== 'ACTIVE' || sub.author_id === null) {
        throw new ApiError('INVALID_BALLOT', 'That photo cannot receive votes');
      }
      if (sub.author_id === caller.userId) throw new ApiError('INVALID_BALLOT', 'You cannot vote for your own photo');
      if (await blockedEitherWay(tx, caller.userId, sub.author_id)) {
        throw new ApiError('INVALID_BALLOT', 'That photo cannot receive your vote');
      }
      // 5. One immutable ballot per voter: the unique constraint decides, never a read-then-write.
      const ins = await tx.query<{ cast_at: Date }>(
        `INSERT INTO votes (id, challenge_id, voter_id, submission_id, cast_at) VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT ON CONSTRAINT votes_one_per_voter DO NOTHING RETURNING cast_at`,
        [randomUUID(), c.id, caller.userId, target, ts(now)],
      );
      if (ins.rows[0]) {
        return { status: 201, ballot: { submissionId: target, castAt: ins.rows[0].cast_at.getTime() }, purge: due };
      }
      const prior = await tx.query<{ submission_id: string; cast_at: Date }>(
        'SELECT submission_id, cast_at FROM votes WHERE challenge_id = $1 AND voter_id = $2',
        [c.id, caller.userId],
      );
      const p = prior.rows[0]!;
      if (p.submission_id !== target) throw new ApiError('ALREADY_VOTED', 'You have already voted in this challenge');
      return { status: 200, ballot: { submissionId: p.submission_id, castAt: p.cast_at.getTime() }, purge: due };
    });
    await deleteObjects(deps.store, purge);
    reply.code(status);
    return { challengeId, submissionId: ballot.submissionId, castAtEpochMillis: ballot.castAt };
  });

  // ---- Media -------------------------------------------------------------------------------

  app.get('/v1/media/:mediaId', async (req, reply) => {
    const caller = callerOf(req);
    const mediaId = pathId((req.params as { mediaId: string }).mediaId);
    type Row = {
      owner_id: string | null;
      storage_key: string;
      sha256: string;
      purged_at: Date | null;
      challenge_id: string;
      room_id: string;
      state: ChallengeRow['state'];
      submission_deadline: Date | null;
      voting_deadline: Date | null;
      completed_at: Date | null;
      submission_status: string | null;
    };
    const load = async (): Promise<Row | undefined> =>
      (
        await deps.pool.query<Row>(
          `SELECT m.owner_id, m.storage_key, m.sha256, m.purged_at, m.challenge_id, c.room_id, c.state,
                  c.submission_deadline, c.voting_deadline, c.completed_at,
                  (SELECT s.status FROM submissions s WHERE s.full_media_id = m.id OR s.thumb_media_id = m.id LIMIT 1)
                    AS submission_status
             FROM media m JOIN challenges c ON c.id = m.challenge_id
             JOIN rooms r ON r.id = c.room_id AND r.deleted_at IS NULL
            WHERE m.id = $1`,
          [mediaId],
        )
      ).rows[0];
    let m = await load();
    if (!m || m.purged_at !== null) throw notFound();
    // Lazy state evaluation: a closed submission window opens the gallery, an archive purges it.
    const now = deps.clock.now();
    const due =
      (m.state === 'OPEN_FOR_SUBMISSIONS' && now >= (ms(m.submission_deadline) ?? Infinity)) ||
      (m.state === 'VOTING' && now >= (ms(m.voting_deadline) ?? Infinity)) ||
      (m.state === 'COMPLETED' && now >= (ms(m.completed_at) ?? Infinity) + 30 * DAY_MS);
    if (due) {
      await ensureAdvanced(deps, m.challenge_id);
      m = await load();
      if (!m || m.purged_at !== null) throw notFound();
    }
    const isOwner = m.owner_id !== null && m.owner_id === caller.userId;
    if (!isOwner) {
      if (m.owner_id === null) throw notFound();
      if (!(await activeMembership(deps.pool, m.room_id, caller.userId))) throw notFound();
      if (m.state !== 'VOTING' && m.state !== 'COMPLETED' && m.state !== 'ARCHIVED') throw notFound();
      if (m.submission_status !== 'ACTIVE') throw notFound();
      if (await blockedEitherWay(deps.pool, caller.userId, m.owner_id)) throw notFound();
    }
    const etag = `"${m.sha256}"`;
    reply
      .header('Cache-Control', 'private, max-age=300')
      .header('X-Content-Type-Options', 'nosniff')
      .header('Content-Disposition', 'inline')
      .header('ETag', etag);
    if (req.headers['if-none-match'] === etag) {
      reply.code(304);
      return reply.send();
    }
    const bytes = await deps.store.get(m.storage_key);
    if (!bytes) throw notFound();
    reply.type('image/jpeg');
    return reply.send(bytes);
  });
}

