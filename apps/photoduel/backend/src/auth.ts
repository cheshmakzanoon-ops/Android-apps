import type { FastifyReply, FastifyRequest } from 'fastify';
import type { AppDeps, Caller } from './deps.js';
import { newOpaqueToken, OPAQUE_TOKEN_RE, sha256 } from './lib/crypto.js';
import { ts, type Queryable } from './lib/db.js';
import { ApiError } from './lib/errors.js';

const SLIDE_AFTER_MS = 60_000;

/** Issues a new opaque session token. Only its SHA-256 is stored. */
export async function issueSession(q: Queryable, deps: AppDeps, userId: string): Promise<{ token: string; expiresAtEpochMillis: number }> {
  const now = deps.clock.now();
  const token = newOpaqueToken();
  const expires = now + deps.config.sessionTtlMs;
  await q.query(
    'INSERT INTO sessions (token_hash, user_id, created_at, last_used_at, expires_at) VALUES ($1, $2, $3, $3, $4)',
    [sha256(token), userId, ts(now), ts(expires)],
  );
  return { token, expiresAtEpochMillis: expires };
}

function bearer(req: FastifyRequest): string | null {
  const h = req.headers.authorization;
  if (typeof h !== 'string') return null;
  const m = /^Bearer ([A-Za-z0-9_-]+)$/.exec(h);
  if (!m || !OPAQUE_TOKEN_RE.test(m[1]!)) return null;
  return m[1]!;
}

/**
 * Resolves the caller from the bearer token on every non-public route. Missing, malformed,
 * unknown, expired or revoked tokens and suspended or deleted accounts all get the same 401.
 * Expiry slides: each use pushes it to now + SESSION_TTL_DAYS.
 */
export function authHook(deps: AppDeps) {
  return async function authenticate(req: FastifyRequest, _reply: FastifyReply): Promise<void> {
    req.caller = null;
    if (req.routeOptions.config?.public === true) return;
    const token = bearer(req);
    if (!token) throw new ApiError('UNAUTHENTICATED', 'Authentication required');
    const hash = sha256(token);
    const now = deps.clock.now();
    const { rows } = await deps.pool.query<{
      user_id: string;
      display_name: string | null;
      last_used_at: Date;
      expires_at: Date;
      suspended_at: Date | null;
      deleted_at: Date | null;
    }>(
      `SELECT s.user_id, s.last_used_at, s.expires_at, u.display_name, u.suspended_at, u.deleted_at
         FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = $1`,
      [hash],
    );
    const row = rows[0];
    if (!row || row.expires_at.getTime() <= now || row.deleted_at !== null || row.display_name === null) {
      throw new ApiError('UNAUTHENTICATED', 'Session is invalid or expired');
    }
    if (row.suspended_at !== null) throw new ApiError('UNAUTHENTICATED', 'Account is suspended');
    if (now - row.last_used_at.getTime() >= SLIDE_AFTER_MS) {
      await deps.pool.query('UPDATE sessions SET last_used_at = $2, expires_at = $3 WHERE token_hash = $1', [
        hash,
        ts(now),
        ts(now + deps.config.sessionTtlMs),
      ]);
    }
    const caller: Caller = { userId: row.user_id, displayName: row.display_name, tokenHash: hash };
    req.caller = caller;
  };
}

export function callerOf(req: FastifyRequest): Caller {
  if (!req.caller) throw new ApiError('UNAUTHENTICATED', 'Authentication required');
  return req.caller;
}
