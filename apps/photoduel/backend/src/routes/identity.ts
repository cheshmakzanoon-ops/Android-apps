import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { callerOf, issueSession } from '../auth.js';
import type { AppDeps } from '../deps.js';
import { HOUR_MS } from '../lib/clock.js';
import { hashIp, hashRecoveryCode, newRecoveryCode, normalizeRecoveryCode } from '../lib/crypto.js';
import { ts, withTx, type Queryable } from '../lib/db.js';
import { ApiError } from '../lib/errors.js';
import { assertQuota, consumeQuota, recordEvent } from '../lib/rateLimit.js';
import { deleteAccount } from '../accountDeletion.js';
import { DisplayName, EmptyBody, parseBody } from '../validation.js';

const GuestBody = z.strictObject({ displayName: DisplayName });
const RestoreBody = z.strictObject({ recoveryCode: z.string().max(64) });
const RenameBody = z.strictObject({ displayName: DisplayName });
const DeleteBody = z.strictObject({ confirm: z.literal('DELETE') });

async function profile(q: Queryable, userId: string) {
  const { rows } = await q.query<{ id: string; display_name: string; has_code: boolean }>(
    `SELECT u.id, u.display_name, EXISTS (SELECT 1 FROM recovery_codes rc WHERE rc.user_id = u.id) AS has_code
       FROM users u WHERE u.id = $1`,
    [userId],
  );
  const r = rows[0]!;
  return { id: r.id, displayName: r.display_name, hasRecoveryCode: r.has_code };
}

/** Generates a code, stores only its scrypt hash (replacing any earlier code) and returns it once. */
async function rotateRecoveryCode(q: Queryable, deps: AppDeps, userId: string): Promise<string> {
  const code = newRecoveryCode();
  const hash = await hashRecoveryCode(normalizeRecoveryCode(code)!, deps.config.recoveryCodePepper, deps.config.recoveryScryptN);
  await q.query(
    `INSERT INTO recovery_codes (user_id, code_hash, created_at) VALUES ($1, $2, $3)
     ON CONFLICT (user_id) DO UPDATE SET code_hash = EXCLUDED.code_hash, created_at = EXCLUDED.created_at`,
    [userId, hash, ts(deps.clock.now())],
  );
  return code;
}

export function identityRoutes(app: FastifyInstance, deps: AppDeps): void {
  const ipSubject = (ip: string) => hashIp(ip, deps.config.ipHashSalt);

  app.post('/v1/identity/guest', { config: { public: true } }, async (req, reply) => {
    const body = parseBody(GuestBody, req.body);
    const now = deps.clock.now();
    const result = await withTx(deps.pool, async (tx) => {
      await consumeQuota(
        tx,
        { bucket: 'guest.create', subject: ipSubject(req.ip), windowMs: HOUR_MS, maxCount: deps.config.limits.guestsPerIpPerHour },
        now,
      );
      const id = randomUUID();
      await tx.query('INSERT INTO users (id, display_name, created_at) VALUES ($1, $2, $3)', [id, body.displayName, ts(now)]);
      const session = await issueSession(tx, deps, id);
      return { user: { id, displayName: body.displayName, hasRecoveryCode: false }, session };
    });
    reply.code(201);
    return result;
  });

  app.post('/v1/identity/restore', { config: { public: true } }, async (req) => {
    const body = parseBody(RestoreBody, req.body);
    const subject = ipSubject(req.ip);
    const rule = {
      bucket: 'restore.fail',
      subject,
      windowMs: HOUR_MS,
      maxCount: deps.config.limits.restoreFailuresPerIpPerHour,
    };
    const outcome = await withTx(deps.pool, async (tx) => {
      const now = deps.clock.now();
      // The advisory lock taken here serialises attempts from one IP hash, so parallel guesses
      // cannot exceed the failure budget.
      await assertQuota(tx, rule, now);
      const normalized = normalizeRecoveryCode(body.recoveryCode);
      let userId: string | null = null;
      if (normalized) {
        const hash = await hashRecoveryCode(normalized, deps.config.recoveryCodePepper, deps.config.recoveryScryptN);
        const { rows } = await tx.query<{ user_id: string }>(
          `SELECT rc.user_id FROM recovery_codes rc JOIN users u ON u.id = rc.user_id
            WHERE rc.code_hash = $1 AND u.deleted_at IS NULL AND u.suspended_at IS NULL FOR UPDATE OF rc`,
          [hash],
        );
        userId = rows[0]?.user_id ?? null;
      }
      if (!userId) {
        await recordEvent(tx, rule.bucket, rule.subject, now);
        return { ok: false as const };
      }
      const recoveryCode = await rotateRecoveryCode(tx, deps, userId);
      const session = await issueSession(tx, deps, userId);
      return { ok: true as const, user: await profile(tx, userId), session, recoveryCode };
    });
    if (!outcome.ok) {
      throw new ApiError('UNAUTHENTICATED', 'INVALID_RECOVERY_CODE: the recovery code is not valid');
    }
    return { user: outcome.user, session: outcome.session, recoveryCode: outcome.recoveryCode };
  });

  app.post('/v1/identity/recovery-code', async (req, reply) => {
    const caller = callerOf(req);
    parseBody(EmptyBody, req.body);
    const recoveryCode = await withTx(deps.pool, (tx) => rotateRecoveryCode(tx, deps, caller.userId));
    reply.code(201);
    return { recoveryCode };
  });

  app.post('/v1/identity/logout', async (req, reply) => {
    const caller = callerOf(req);
    parseBody(EmptyBody, req.body);
    await deps.pool.query('DELETE FROM sessions WHERE token_hash = $1', [caller.tokenHash]);
    reply.code(204);
  });

  app.get('/v1/me', async (req) => profile(deps.pool, callerOf(req).userId));

  app.patch('/v1/me', async (req) => {
    const caller = callerOf(req);
    const body = parseBody(RenameBody, req.body);
    await deps.pool.query('UPDATE users SET display_name = $2 WHERE id = $1 AND deleted_at IS NULL', [
      caller.userId,
      body.displayName,
    ]);
    return profile(deps.pool, caller.userId);
  });

  app.delete('/v1/me', async (req, reply) => {
    const caller = callerOf(req);
    parseBody(DeleteBody, req.body);
    await deleteAccount(deps, caller.userId, `user:${caller.userId}`);
    reply.code(204);
  });
}
