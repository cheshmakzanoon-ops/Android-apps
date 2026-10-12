import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { activeMembership, lockLiveCaller } from '../access.js';
import { softDeleteRoom } from '../accountDeletion.js';
import { callerOf } from '../auth.js';
import type { AppDeps } from '../deps.js';
import { advanceLocked, deleteObjects, ensureRoomAdvanced, lockChallenge, type ChallengeRow } from '../lifecycle.js';
import { audit } from '../lib/audit.js';
import { DAY_MS, HOUR_MS } from '../lib/clock.js';
import { newOpaqueToken, OPAQUE_TOKEN_RE, sha256 } from '../lib/crypto.js';
import { ts, withTx, type Tx } from '../lib/db.js';
import { ApiError, notFound } from '../lib/errors.js';
import { consumeQuota } from '../lib/rateLimit.js';
import { roomDetailJson } from '../views.js';
import { EmptyBody, parseBody, pathId, RoomName } from '../validation.js';

const CreateRoomBody = z.strictObject({ name: RoomName });
const CreateInvitationBody = z.strictObject({
  ttlHours: z.union([z.literal(24), z.literal(72), z.literal(168)]).optional(),
});

const inviteUnavailable = () => new ApiError('INVITE_UNAVAILABLE', 'This invitation is not available');

/** Locks the room row; 404 when the room is deleted or the caller is not an active member. */
async function lockRoomForMember(tx: Tx, roomId: string, userId: string) {
  const { rows } = await tx.query<{ id: string; owner_id: string; capacity: number; name: string }>(
    'SELECT id, owner_id, capacity, name FROM rooms WHERE id = $1 AND deleted_at IS NULL FOR UPDATE',
    [roomId],
  );
  const room = rows[0];
  if (!room) throw notFound();
  const m = await activeMembership(tx, roomId, userId);
  if (!m) throw notFound();
  return { room, membership: m };
}

async function memberCount(tx: Tx, roomId: string): Promise<number> {
  const { rows } = await tx.query<{ n: string }>(
    'SELECT count(*) AS n FROM room_members WHERE room_id = $1 AND left_at IS NULL',
    [roomId],
  );
  return Number(rows[0]?.n ?? 0);
}

/**
 * Leaving and removal are blocked while a challenge is OPEN_FOR_SUBMISSIONS or VOTING (the roster
 * is frozen). Due transitions are applied first so a closed window does not keep blocking.
 */
async function assertRosterNotFrozen(tx: Tx, roomId: string, now: number): Promise<string[]> {
  const { rows } = await tx.query<{ id: string }>(
    `SELECT id FROM challenges WHERE room_id = $1 AND state IN ('OPEN_FOR_SUBMISSIONS', 'VOTING') ORDER BY id`,
    [roomId],
  );
  const purge: string[] = [];
  for (const r of rows) {
    const locked = (await lockChallenge(tx, r.id)) as ChallengeRow;
    const advanced = await advanceLocked(tx, locked, now);
    purge.push(...advanced.purge);
    if (advanced.row.state === 'OPEN_FOR_SUBMISSIONS' || advanced.row.state === 'VOTING') {
      throw new ApiError('ROSTER_FROZEN', 'Membership cannot change while a challenge is running');
    }
  }
  return purge;
}

export function roomRoutes(app: FastifyInstance, deps: AppDeps): void {
  app.get('/v1/rooms', async (req) => {
    const caller = callerOf(req);
    const mine = await deps.pool.query<{ id: string }>(
      `SELECT r.id FROM rooms r JOIN room_members m ON m.room_id = r.id
        WHERE m.user_id = $1 AND m.left_at IS NULL AND r.deleted_at IS NULL`,
      [caller.userId],
    );
    await ensureRoomAdvanced(deps, deps.pool, mine.rows.map((r) => r.id));
    const { rows } = await deps.pool.query<{
      id: string;
      name: string;
      capacity: number;
      member_count: string;
      active_state: string | null;
    }>(
      `SELECT r.id, r.name, r.capacity,
              (SELECT count(*) FROM room_members x WHERE x.room_id = r.id AND x.left_at IS NULL) AS member_count,
              (SELECT c.state FROM challenges c WHERE c.room_id = r.id
                  AND c.state IN ('CREATED', 'OPEN_FOR_SUBMISSIONS', 'VOTING') LIMIT 1) AS active_state
         FROM rooms r JOIN room_members m ON m.room_id = r.id
        WHERE m.user_id = $1 AND m.left_at IS NULL AND r.deleted_at IS NULL
        ORDER BY m.joined_at DESC, r.id`,
      [caller.userId],
    );
    return {
      rooms: rows.map((r) => ({
        id: r.id,
        name: r.name,
        memberCount: Number(r.member_count),
        capacity: r.capacity,
        activeChallengeState: r.active_state,
      })),
    };
  });

  app.post('/v1/rooms', async (req, reply) => {
    const caller = callerOf(req);
    const body = parseBody(CreateRoomBody, req.body);
    const now = deps.clock.now();
    const id = randomUUID();
    await withTx(deps.pool, async (tx) => {
      await consumeQuota(
        tx,
        { bucket: 'room.create', subject: caller.userId, windowMs: DAY_MS, maxCount: deps.config.limits.roomsPerUserPerDay },
        now,
      );
      await tx.query('INSERT INTO rooms (id, name, owner_id, capacity, created_at) VALUES ($1, $2, $3, $4, $5)', [
        id,
        body.name,
        caller.userId,
        deps.config.limits.roomCapacity,
        ts(now),
      ]);
      await tx.query(
        `INSERT INTO room_members (id, room_id, user_id, role, joined_at) VALUES ($1, $2, $3, 'OWNER', $4)`,
        [randomUUID(), id, caller.userId, ts(now)],
      );
    });
    reply.code(201);
    return roomDetailJson(deps.pool, id, caller.userId);
  });

  app.get('/v1/rooms/:roomId', async (req) => {
    const caller = callerOf(req);
    const roomId = pathId((req.params as { roomId: string }).roomId);
    const detail = await roomDetailJson(deps.pool, roomId, caller.userId);
    if (!detail) throw notFound();
    return detail;
  });

  app.post('/v1/rooms/:roomId/leave', async (req, reply) => {
    const caller = callerOf(req);
    const roomId = pathId((req.params as { roomId: string }).roomId);
    parseBody(EmptyBody, req.body);
    const purge = await withTx(deps.pool, async (tx) => {
      const { membership } = await lockRoomForMember(tx, roomId, caller.userId);
      if (membership.role === 'OWNER') {
        throw new ApiError('FORBIDDEN', 'The owner cannot leave the room; delete it instead');
      }
      const now = deps.clock.now();
      const keys = await assertRosterNotFrozen(tx, roomId, now);
      await tx.query(
        `UPDATE room_members SET left_at = $3, left_reason = 'LEFT' WHERE room_id = $1 AND user_id = $2 AND left_at IS NULL`,
        [roomId, caller.userId, ts(now)],
      );
      return keys;
    });
    await deleteObjects(deps.store, purge);
    reply.code(204);
  });

  app.delete('/v1/rooms/:roomId/members/:userId', async (req, reply) => {
    const caller = callerOf(req);
    const params = req.params as { roomId: string; userId: string };
    const roomId = pathId(params.roomId);
    const targetId = pathId(params.userId);
    const purge = await withTx(deps.pool, async (tx) => {
      const { room, membership } = await lockRoomForMember(tx, roomId, caller.userId);
      if (membership.role !== 'OWNER') throw new ApiError('FORBIDDEN', 'Only the owner can remove members');
      if (targetId === room.owner_id) throw new ApiError('FORBIDDEN', 'The owner cannot be removed');
      const target = await activeMembership(tx, roomId, targetId);
      if (!target) throw notFound();
      const now = deps.clock.now();
      const keys = await assertRosterNotFrozen(tx, roomId, now);
      await tx.query(
        `UPDATE room_members SET left_at = $3, left_reason = 'REMOVED' WHERE room_id = $1 AND user_id = $2 AND left_at IS NULL`,
        [roomId, targetId, ts(now)],
      );
      await tx.query(
        'UPDATE invitations SET revoked_at = $3 WHERE room_id = $1 AND created_by = $2 AND revoked_at IS NULL',
        [roomId, targetId, ts(now)],
      );
      return keys;
    });
    await deleteObjects(deps.store, purge);
    reply.code(204);
  });

  app.delete('/v1/rooms/:roomId', async (req, reply) => {
    const caller = callerOf(req);
    const roomId = pathId((req.params as { roomId: string }).roomId);
    const purge = await withTx(deps.pool, async (tx) => {
      const { membership } = await lockRoomForMember(tx, roomId, caller.userId);
      if (membership.role !== 'OWNER') throw new ApiError('FORBIDDEN', 'Only the owner can delete the room');
      const now = deps.clock.now();
      const keys = await softDeleteRoom(tx, roomId, now);
      await audit(tx, now, `user:${caller.userId}`, 'ROOM_DELETED', 'ROOM', roomId);
      return keys;
    });
    await deleteObjects(deps.store, purge);
    reply.code(204);
  });

  // ---- Invitations -------------------------------------------------------------------------

  app.post('/v1/rooms/:roomId/invitations', async (req, reply) => {
    const caller = callerOf(req);
    const roomId = pathId((req.params as { roomId: string }).roomId);
    const body = parseBody(CreateInvitationBody, req.body);
    const ttlHours = body.ttlHours ?? 72;
    const token = newOpaqueToken();
    const now = deps.clock.now();
    const created = await withTx(deps.pool, async (tx) => {
      await lockLiveCaller(tx, caller.userId);
      const { room } = await lockRoomForMember(tx, roomId, caller.userId);
      const members = await memberCount(tx, roomId);
      if (members >= room.capacity) throw new ApiError('ROOM_FULL', 'The room is full');
      const { rows } = await tx.query<{ n: string }>(
        `SELECT count(*) AS n FROM invitations
          WHERE room_id = $1 AND revoked_at IS NULL AND expires_at > $2 AND uses < max_uses`,
        [roomId, ts(now)],
      );
      if (Number(rows[0]?.n ?? 0) >= deps.config.limits.activeInvitationsPerRoom) {
        throw new ApiError('CONFLICT', 'This room already has the maximum number of active invitations');
      }
      const id = randomUUID();
      const expires = now + ttlHours * HOUR_MS;
      await tx.query(
        `INSERT INTO invitations (id, room_id, token_hash, created_by, created_at, expires_at, max_uses)
         VALUES ($1, $2, $3, $4, $5, $6, $7)`,
        [id, roomId, sha256(token), caller.userId, ts(now), ts(expires), room.capacity - 1],
      );
      return { id, expires };
    });
    reply.code(201);
    return {
      id: created.id,
      token,
      expiresAtEpochMillis: created.expires,
      url: `photoduel://invite/${token}`,
    };
  });

  app.get('/v1/rooms/:roomId/invitations', async (req) => {
    const caller = callerOf(req);
    const roomId = pathId((req.params as { roomId: string }).roomId);
    if (!(await activeMembership(deps.pool, roomId, caller.userId))) throw notFound();
    const { rows } = await deps.pool.query<{ id: string; expires_at: Date; uses: number; max_uses: number; created_by: string }>(
      `SELECT id, expires_at, uses, max_uses, created_by FROM invitations
        WHERE room_id = $1 AND revoked_at IS NULL AND expires_at > $2 AND uses < max_uses
        ORDER BY created_at DESC, id`,
      [roomId, ts(deps.clock.now())],
    );
    return {
      invitations: rows.map((r) => ({
        id: r.id,
        expiresAtEpochMillis: r.expires_at.getTime(),
        usesRemaining: r.max_uses - r.uses,
        createdByUserId: r.created_by,
      })),
    };
  });

  app.delete('/v1/rooms/:roomId/invitations/:invitationId', async (req, reply) => {
    const caller = callerOf(req);
    const params = req.params as { roomId: string; invitationId: string };
    const roomId = pathId(params.roomId);
    const invitationId = pathId(params.invitationId);
    await withTx(deps.pool, async (tx) => {
      const { membership } = await lockRoomForMember(tx, roomId, caller.userId);
      const { rows } = await tx.query<{ created_by: string; revoked_at: Date | null }>(
        'SELECT created_by, revoked_at FROM invitations WHERE id = $1 AND room_id = $2 FOR UPDATE',
        [invitationId, roomId],
      );
      const inv = rows[0];
      if (!inv) throw notFound();
      if (inv.created_by !== caller.userId && membership.role !== 'OWNER') {
        throw new ApiError('FORBIDDEN', 'Only the creator or the owner can revoke this invitation');
      }
      if (inv.revoked_at === null) {
        await tx.query('UPDATE invitations SET revoked_at = $2 WHERE id = $1', [invitationId, ts(deps.clock.now())]);
      }
    });
    reply.code(204);
  });

  app.get('/v1/invitations/:token', async (req) => {
    callerOf(req);
    const token = (req.params as { token: string }).token;
    if (!OPAQUE_TOKEN_RE.test(token)) throw inviteUnavailable();
    const { rows } = await deps.pool.query<{
      room_name: string;
      owner_name: string | null;
      capacity: number;
      expires_at: Date;
      member_count: string;
    }>(
      `SELECT r.name AS room_name, u.display_name AS owner_name, r.capacity, i.expires_at,
              (SELECT count(*) FROM room_members m WHERE m.room_id = r.id AND m.left_at IS NULL) AS member_count
         FROM invitations i JOIN rooms r ON r.id = i.room_id JOIN users u ON u.id = r.owner_id
        WHERE i.token_hash = $1 AND i.revoked_at IS NULL AND i.expires_at > $2 AND i.uses < i.max_uses
          AND r.deleted_at IS NULL`,
      [sha256(token), ts(deps.clock.now())],
    );
    const r = rows[0];
    if (!r || r.owner_name === null) throw inviteUnavailable();
    return {
      roomName: r.room_name,
      ownerDisplayName: r.owner_name,
      memberCount: Number(r.member_count),
      capacity: r.capacity,
      expiresAtEpochMillis: r.expires_at.getTime(),
    };
  });

  app.post('/v1/invitations/:token/join', async (req) => {
    const caller = callerOf(req);
    const token = (req.params as { token: string }).token;
    parseBody(EmptyBody, req.body);
    if (!OPAQUE_TOKEN_RE.test(token)) throw inviteUnavailable();
    const hash = sha256(token);
    const roomId = await withTx(deps.pool, async (tx) => {
      // Lock order: caller, room, then invitation. The use counter changes only under all three.
      await lockLiveCaller(tx, caller.userId);
      const found = await tx.query<{ room_id: string }>('SELECT room_id FROM invitations WHERE token_hash = $1', [hash]);
      const roomIdFound = found.rows[0]?.room_id;
      if (!roomIdFound) throw inviteUnavailable();
      const room = await tx.query<{ id: string; capacity: number }>(
        'SELECT id, capacity FROM rooms WHERE id = $1 AND deleted_at IS NULL FOR UPDATE',
        [roomIdFound],
      );
      if (!room.rows[0]) throw inviteUnavailable();
      const inv = await tx.query<{ id: string; expires_at: Date; uses: number; max_uses: number; revoked_at: Date | null }>(
        'SELECT id, expires_at, uses, max_uses, revoked_at FROM invitations WHERE token_hash = $1 FOR UPDATE',
        [hash],
      );
      const i = inv.rows[0];
      if (!i || i.revoked_at !== null) throw inviteUnavailable();
      // Idempotent for an existing member: no use is consumed.
      if (await activeMembership(tx, roomIdFound, caller.userId)) return roomIdFound;
      const now = deps.clock.now();
      if (i.expires_at.getTime() <= now || i.uses >= i.max_uses) throw inviteUnavailable();
      if ((await memberCount(tx, roomIdFound)) >= room.rows[0].capacity) {
        throw new ApiError('ROOM_FULL', 'The room is full');
      }
      await tx.query('UPDATE invitations SET uses = uses + 1 WHERE id = $1', [i.id]);
      await tx.query(
        `INSERT INTO room_members (id, room_id, user_id, role, joined_at) VALUES ($1, $2, $3, 'MEMBER', $4)`,
        [randomUUID(), roomIdFound, caller.userId, ts(now)],
      );
      return roomIdFound;
    });
    const detail = await roomDetailJson(deps.pool, roomId, caller.userId);
    if (!detail) throw inviteUnavailable();
    return detail;
  });
}
