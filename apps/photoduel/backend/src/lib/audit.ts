import { ts, type Tx } from './db.js';

/** Appends one audit_log row. Details must never contain tokens, photo bytes or EXIF data. */
export async function audit(
  tx: Tx,
  now: number,
  actor: string,
  action: string,
  targetType: string | null,
  targetId: string | null,
  details: Record<string, unknown> = {},
): Promise<void> {
  await tx.query(
    'INSERT INTO audit_log (actor, action, target_type, target_id, details, created_at) VALUES ($1, $2, $3, $4, $5, $6)',
    [actor, action, targetType, targetId, JSON.stringify(details), ts(now)],
  );
}
