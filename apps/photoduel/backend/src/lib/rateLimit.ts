import type { Tx } from './db.js';
import { ts } from './db.js';
import { ApiError } from './errors.js';

/**
 * Fixed-window quotas backed by the rate_events table, so every API instance sees the same counts.
 * A transaction-scoped advisory lock on (bucket, subject) serialises the count-then-insert, so
 * concurrent requests cannot both slip under the limit.
 */
export interface QuotaRule {
  bucket: string;
  subject: string;
  windowMs: number;
  maxCount: number;
  /** Optional cap on the summed amount in the window (for example uploaded bytes per day). */
  maxAmount?: number;
}

export async function lockQuota(tx: Tx, bucket: string, subject: string): Promise<void> {
  await tx.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`rate:${bucket}:${subject}`]);
}

export function windowStart(now: number, windowMs: number): number {
  return Math.floor(now / windowMs) * windowMs;
}

/** Throws RATE_LIMITED when adding `amount` would exceed the rule. Must run inside a transaction. */
export async function assertQuota(tx: Tx, rule: QuotaRule, now: number, amount = 1): Promise<void> {
  await lockQuota(tx, rule.bucket, rule.subject);
  const start = windowStart(now, rule.windowMs);
  const { rows } = await tx.query<{ n: string; total: string | null }>(
    `SELECT count(*) AS n, sum(amount) AS total FROM rate_events
      WHERE bucket = $1 AND subject = $2 AND occurred_at >= $3`,
    [rule.bucket, rule.subject, ts(start)],
  );
  const n = Number(rows[0]?.n ?? 0);
  const total = Number(rows[0]?.total ?? 0);
  const overCount = n + 1 > rule.maxCount;
  const overAmount = rule.maxAmount !== undefined && total + amount > rule.maxAmount;
  if (overCount || overAmount) {
    const retryAfterSeconds = Math.max(1, Math.ceil((start + rule.windowMs - now) / 1000));
    throw new ApiError('RATE_LIMITED', 'Too many requests, try again later', { retryAfterSeconds });
  }
}

export async function recordEvent(tx: Tx, bucket: string, subject: string, now: number, amount = 1): Promise<void> {
  await tx.query('INSERT INTO rate_events (bucket, subject, amount, occurred_at) VALUES ($1, $2, $3, $4)', [
    bucket,
    subject,
    amount,
    ts(now),
  ]);
}

/** Assert and record in one step (the common case). */
export async function consumeQuota(tx: Tx, rule: QuotaRule, now: number, amount = 1): Promise<void> {
  await assertQuota(tx, rule, now, amount);
  await recordEvent(tx, rule.bucket, rule.subject, now, amount);
}
