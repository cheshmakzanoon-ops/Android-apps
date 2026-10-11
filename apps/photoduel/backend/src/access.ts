import type { Queryable } from './lib/db.js';

export const DELETED_PLAYER = 'Deleted player';

export interface Membership {
  roomId: string;
  role: 'OWNER' | 'MEMBER';
  joinedAt: Date;
}

/** Active membership in a room that is not deleted, or null. */
export async function activeMembership(q: Queryable, roomId: string, userId: string): Promise<Membership | null> {
  const { rows } = await q.query<{ role: 'OWNER' | 'MEMBER'; joined_at: Date }>(
    `SELECT m.role, m.joined_at FROM room_members m JOIN rooms r ON r.id = m.room_id
      WHERE m.room_id = $1 AND m.user_id = $2 AND m.left_at IS NULL AND r.deleted_at IS NULL`,
    [roomId, userId],
  );
  const r = rows[0];
  return r ? { roomId, role: r.role, joinedAt: r.joined_at } : null;
}

/** True when either user blocked the other. */
export async function blockedEitherWay(q: Queryable, a: string, b: string): Promise<boolean> {
  if (a === b) return false;
  const { rowCount } = await q.query(
    `SELECT 1 FROM blocks WHERE (blocker_id = $1 AND blocked_id = $2) OR (blocker_id = $2 AND blocked_id = $1) LIMIT 1`,
    [a, b],
  );
  return (rowCount ?? 0) > 0;
}

/** Users blocked by, or blocking, the viewer. */
export async function blockedSet(q: Queryable, userId: string): Promise<Set<string>> {
  const { rows } = await q.query<{ other: string }>(
    `SELECT blocked_id AS other FROM blocks WHERE blocker_id = $1
     UNION SELECT blocker_id AS other FROM blocks WHERE blocked_id = $1`,
    [userId],
  );
  return new Set(rows.map((r) => r.other));
}

/** True when both users are active members of at least one room that is not deleted. */
export async function shareARoom(q: Queryable, a: string, b: string): Promise<boolean> {
  const { rowCount } = await q.query(
    `SELECT 1 FROM room_members ma
       JOIN room_members mb ON mb.room_id = ma.room_id AND mb.user_id = $2 AND mb.left_at IS NULL
       JOIN rooms r ON r.id = ma.room_id AND r.deleted_at IS NULL
      WHERE ma.user_id = $1 AND ma.left_at IS NULL LIMIT 1`,
    [a, b],
  );
  return (rowCount ?? 0) > 0;
}

export const displayNameOf = (name: string | null, deletedAt: Date | null): string =>
  deletedAt !== null || name === null ? DELETED_PLAYER : name;
