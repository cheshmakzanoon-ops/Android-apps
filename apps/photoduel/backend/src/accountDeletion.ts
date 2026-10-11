import type { AppDeps } from './deps.js';
import { deleteObjects, purgeChallengeMedia } from './lifecycle.js';
import { audit } from './lib/audit.js';
import { ts, withTx, type Tx } from './lib/db.js';

/**
 * Soft-deletes a room: hidden at once, live challenges cancelled, invitations revoked and every
 * photo purged now (the worker removes the rows after 7 days). Caller holds no locks yet.
 */
export async function softDeleteRoom(tx: Tx, roomId: string, now: number): Promise<string[]> {
  await tx.query('UPDATE rooms SET deleted_at = $2 WHERE id = $1 AND deleted_at IS NULL', [roomId, ts(now)]);
  const all = await tx.query<{ id: string }>('SELECT id FROM challenges WHERE room_id = $1 ORDER BY id FOR UPDATE', [
    roomId,
  ]);
  await tx.query(
    `UPDATE challenges SET state = 'CANCELLED', cancelled_at = $2
      WHERE room_id = $1 AND state IN ('CREATED', 'OPEN_FOR_SUBMISSIONS', 'VOTING')`,
    [roomId, ts(now)],
  );
  await tx.query('UPDATE invitations SET revoked_at = $2 WHERE room_id = $1 AND revoked_at IS NULL', [roomId, ts(now)]);
  const keys: string[] = [];
  for (const c of all.rows) keys.push(...(await purgeChallengeMedia(tx, c.id, now)));
  await tx.query('UPDATE rooms SET media_purged_at = $2 WHERE id = $1', [roomId, ts(now)]);
  return keys;
}

/**
 * Account deletion (api-contract.md section 2, architecture section 11): sessions and recovery
 * code revoked, every photo purged immediately, memberships ended, rooms the user owns deleted,
 * display name cleared, and ballots kept with voter_id set to NULL so tallies stay reproducible.
 */
export async function deleteAccount(deps: AppDeps, userId: string, actor: string): Promise<void> {
  const keys = await withTx(deps.pool, async (tx) => {
    const now = deps.clock.now();
    const u = await tx.query('SELECT id FROM users WHERE id = $1 AND deleted_at IS NULL FOR UPDATE', [userId]);
    if (u.rowCount === 0) return [];
    const purge: string[] = [];
    const owned = await tx.query<{ id: string }>(
      'SELECT id FROM rooms WHERE owner_id = $1 AND deleted_at IS NULL ORDER BY id FOR UPDATE',
      [userId],
    );
    for (const r of owned.rows) purge.push(...(await softDeleteRoom(tx, r.id, now)));

    const media = await tx.query<{ storage_key: string }>(
      'UPDATE media SET purged_at = $2 WHERE owner_id = $1 AND purged_at IS NULL RETURNING storage_key',
      [userId, ts(now)],
    );
    purge.push(...media.rows.map((m) => m.storage_key));
    await tx.query('UPDATE media SET owner_id = NULL WHERE owner_id = $1', [userId]);
    await tx.query(
      `UPDATE submissions SET author_id = NULL, updated_at = $2,
              status = CASE WHEN status IN ('ACTIVE', 'HIDDEN') THEN 'REMOVED' ELSE status END
        WHERE author_id = $1`,
      [userId, ts(now)],
    );
    await tx.query('UPDATE votes SET voter_id = NULL WHERE voter_id = $1', [userId]);
    await tx.query(
      `UPDATE room_members SET left_at = $2, left_reason = 'ACCOUNT_DELETED' WHERE user_id = $1 AND left_at IS NULL`,
      [userId, ts(now)],
    );
    await tx.query('UPDATE invitations SET revoked_at = $2 WHERE created_by = $1 AND revoked_at IS NULL', [userId, ts(now)]);
    await tx.query('DELETE FROM blocks WHERE blocker_id = $1 OR blocked_id = $1', [userId]);
    await tx.query('DELETE FROM sessions WHERE user_id = $1', [userId]);
    await tx.query('DELETE FROM recovery_codes WHERE user_id = $1', [userId]);
    await tx.query('UPDATE users SET display_name = NULL, deleted_at = $2 WHERE id = $1', [userId, ts(now)]);
    await audit(tx, now, actor, 'ACCOUNT_DELETED', 'USER', userId, { roomsDeleted: owned.rowCount ?? 0 });
    return purge;
  });
  await deleteObjects(deps.store, keys);
}
