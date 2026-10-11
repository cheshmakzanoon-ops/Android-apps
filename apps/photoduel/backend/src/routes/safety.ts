import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { activeMembership, displayNameOf, shareARoom } from '../access.js';
import { callerOf } from '../auth.js';
import type { AppDeps } from '../deps.js';
import { audit } from '../lib/audit.js';
import { DAY_MS } from '../lib/clock.js';
import { isUuid, ts, withTx } from '../lib/db.js';
import { ApiError, notFound } from '../lib/errors.js';
import { consumeQuota } from '../lib/rateLimit.js';
import { parseBody, pathId } from '../validation.js';

const ReportBody = z.strictObject({
  targetType: z.enum(['SUBMISSION', 'USER']),
  targetId: z.string().max(64),
  reason: z.enum(['INAPPROPRIATE', 'HARASSMENT', 'PRIVACY', 'SPAM', 'NOT_A_PHOTO_OF_PROMPT', 'OTHER']),
  details: z
    .string()
    .max(280)
    .transform((s) => s.trim())
    .optional()
    .nullable(),
});

export function safetyRoutes(app: FastifyInstance, deps: AppDeps): void {
  app.post('/v1/reports', async (req, reply) => {
    const caller = callerOf(req);
    const body = parseBody(ReportBody, req.body);
    if (!isUuid(body.targetId)) throw notFound();
    const targetId = body.targetId.toLowerCase();
    const status = await withTx(deps.pool, async (tx) => {
      const now = deps.clock.now();
      if (body.targetType === 'USER') {
        if (targetId === caller.userId) throw new ApiError('VALIDATION', 'You cannot report yourself', { status: 422 });
        if (!(await shareARoom(tx, caller.userId, targetId))) throw notFound();
      } else {
        const s = await tx.query<{ author_id: string | null; room_id: string; state: string }>(
          `SELECT s.author_id, c.room_id, c.state FROM submissions s JOIN challenges c ON c.id = s.challenge_id
            WHERE s.id = $1 FOR UPDATE OF s`,
          [targetId],
        );
        const sub = s.rows[0];
        if (!sub || sub.author_id === null) throw notFound();
        if (!(await activeMembership(tx, sub.room_id, caller.userId))) throw notFound();
        if (sub.author_id === caller.userId) {
          throw new ApiError('VALIDATION', 'You cannot report your own photo', { status: 422 });
        }
        // Other members' photos are only visible once voting has opened.
        if (!['VOTING', 'COMPLETED', 'ARCHIVED'].includes(sub.state)) throw notFound();
      }
      const existing = await tx.query(
        `SELECT 1 FROM reports WHERE reporter_id = $1 AND target_type = $2 AND target_id = $3 AND status = 'OPEN'`,
        [caller.userId, body.targetType, targetId],
      );
      if ((existing.rowCount ?? 0) > 0) return 200;
      await consumeQuota(
        tx,
        { bucket: 'report', subject: caller.userId, windowMs: DAY_MS, maxCount: deps.config.limits.reportsPerUserPerDay },
        now,
      );
      await tx.query(
        `INSERT INTO reports (id, reporter_id, target_type, target_id, reason, details, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7)`,
        [randomUUID(), caller.userId, body.targetType, targetId, body.reason, body.details || null, ts(now)],
      );
      if (body.targetType === 'SUBMISSION') {
        // Auto-hide at N distinct reporters with open reports, pending moderator review.
        const n = await tx.query<{ n: string }>(
          `SELECT count(DISTINCT reporter_id) AS n FROM reports
            WHERE target_type = 'SUBMISSION' AND target_id = $1 AND status = 'OPEN'`,
          [targetId],
        );
        if (Number(n.rows[0]?.n ?? 0) >= deps.config.limits.autoHideReporters) {
          const hidden = await tx.query(
            `UPDATE submissions SET status = 'HIDDEN', hidden_at = $2, hidden_reason = 'AUTO_REPORT_THRESHOLD'
              WHERE id = $1 AND status = 'ACTIVE'`,
            [targetId, ts(now)],
          );
          if ((hidden.rowCount ?? 0) > 0) {
            await audit(tx, now, 'system', 'SUBMISSION_AUTO_HIDDEN', 'SUBMISSION', targetId, {
              reporters: Number(n.rows[0]?.n ?? 0),
            });
          }
        }
      }
      return 201;
    });
    reply.code(status);
    return { status: 'received' };
  });

  app.get('/v1/blocks', async (req) => {
    const caller = callerOf(req);
    const { rows } = await deps.pool.query<{ blocked_id: string; display_name: string | null; deleted_at: Date | null }>(
      `SELECT b.blocked_id, u.display_name, u.deleted_at FROM blocks b JOIN users u ON u.id = b.blocked_id
        WHERE b.blocker_id = $1 ORDER BY b.created_at, b.blocked_id`,
      [caller.userId],
    );
    return {
      blocked: rows.map((r) => ({ userId: r.blocked_id, displayName: displayNameOf(r.display_name, r.deleted_at) })),
    };
  });

  app.put('/v1/blocks/:userId', async (req, reply) => {
    const caller = callerOf(req);
    const targetId = pathId((req.params as { userId: string }).userId);
    if (targetId === caller.userId) throw new ApiError('VALIDATION', 'You cannot block yourself', { status: 422 });
    if (!(await shareARoom(deps.pool, caller.userId, targetId))) throw notFound();
    await deps.pool.query(
      `INSERT INTO blocks (blocker_id, blocked_id, created_at) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`,
      [caller.userId, targetId, ts(deps.clock.now())],
    );
    reply.code(204);
  });

  app.delete('/v1/blocks/:userId', async (req, reply) => {
    const caller = callerOf(req);
    const targetId = pathId((req.params as { userId: string }).userId);
    await deps.pool.query('DELETE FROM blocks WHERE blocker_id = $1 AND blocked_id = $2', [caller.userId, targetId]);
    reply.code(204);
  });
}
