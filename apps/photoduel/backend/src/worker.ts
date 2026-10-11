import type { AppDeps } from './deps.js';
import { ARCHIVE_AFTER_MS, deleteObjects, ensureAdvanced, purgeChallengeMedia } from './lifecycle.js';
import { DAY_MS, HOUR_MS } from './lib/clock.js';
import { ts, withTx } from './lib/db.js';

export interface WorkerStats {
  advanced: number;
  cancelledPurged: number;
  deletedRoomsPurged: number;
  roomsRemoved: number;
  orphanObjectsDeleted: number;
  sessionsDeleted: number;
  rateEventsDeleted: number;
  auditRowsDeleted: number;
  reportsDeleted: number;
}

const BATCH = 200;

/**
 * One pass of background work. Safe on several instances at once: every transition takes the
 * challenge row lock and re-checks under it, and every purge is an idempotent UPDATE ... WHERE
 * purged_at IS NULL.
 */
export async function runWorkerOnce(deps: AppDeps): Promise<WorkerStats> {
  const now = deps.clock.now();
  const stats: WorkerStats = {
    advanced: 0,
    cancelledPurged: 0,
    deletedRoomsPurged: 0,
    roomsRemoved: 0,
    orphanObjectsDeleted: 0,
    sessionsDeleted: 0,
    rateEventsDeleted: 0,
    auditRowsDeleted: 0,
    reportsDeleted: 0,
  };

  // 1. Deadlines: OPEN -> VOTING/COMPLETED, VOTING -> COMPLETED, COMPLETED -> ARCHIVED (+ purge).
  const due = await deps.pool.query<{ id: string }>(
    `SELECT id FROM challenges WHERE
        (state = 'OPEN_FOR_SUBMISSIONS' AND submission_deadline <= $1)
     OR (state = 'VOTING' AND voting_deadline <= $1)
     OR (state = 'COMPLETED' AND completed_at <= $2)
     ORDER BY id LIMIT ${BATCH}`,
    [ts(now), ts(now - ARCHIVE_AFTER_MS)],
  );
  for (const r of due.rows) {
    await ensureAdvanced(deps, r.id);
    stats.advanced++;
  }

  // 2. Cancelled challenges: photos purged (normally done at cancel time; this catches stragglers).
  const cancelled = await deps.pool.query<{ id: string }>(
    `SELECT DISTINCT c.id FROM challenges c JOIN media m ON m.challenge_id = c.id AND m.purged_at IS NULL
      WHERE c.state = 'CANCELLED' LIMIT ${BATCH}`,
  );
  for (const c of cancelled.rows) {
    const keys = await withTx(deps.pool, (tx) => purgeChallengeMedia(tx, c.id, now));
    await deleteObjects(deps.store, keys);
    stats.cancelledPurged++;
  }

  // 3. Deleted rooms: photos purged, rows removed 7 days after deletion.
  const deletedRooms = await deps.pool.query<{ id: string }>(
    `SELECT DISTINCT c.room_id AS id FROM challenges c JOIN rooms r ON r.id = c.room_id
       JOIN media m ON m.challenge_id = c.id AND m.purged_at IS NULL
      WHERE r.deleted_at IS NOT NULL LIMIT ${BATCH}`,
  );
  for (const room of deletedRooms.rows) {
    const keys = await withTx(deps.pool, async (tx) => {
      const chs = await tx.query<{ id: string }>('SELECT id FROM challenges WHERE room_id = $1', [room.id]);
      const out: string[] = [];
      for (const c of chs.rows) out.push(...(await purgeChallengeMedia(tx, c.id, now)));
      await tx.query('UPDATE rooms SET media_purged_at = $2 WHERE id = $1', [room.id, ts(now)]);
      return out;
    });
    await deleteObjects(deps.store, keys);
    stats.deletedRoomsPurged++;
  }
  const removed = await deps.pool.query('DELETE FROM rooms WHERE deleted_at IS NOT NULL AND deleted_at <= $1', [
    ts(now - 7 * DAY_MS),
  ]);
  stats.roomsRemoved = removed.rowCount ?? 0;

  // 4. Withdrawn or removed submissions whose media survived a failed delete.
  const stale = await deps.pool.query<{ storage_key: string }>(
    `UPDATE media m SET purged_at = $1 FROM submissions s
      WHERE (s.full_media_id = m.id OR s.thumb_media_id = m.id) AND s.status IN ('WITHDRAWN', 'REMOVED')
        AND m.purged_at IS NULL RETURNING m.storage_key`,
    [ts(now)],
  );
  await deleteObjects(deps.store, stale.rows.map((r) => r.storage_key));

  // 5. Orphan objects: older than one hour with no live media row (failed commits, purged rows).
  let batch: string[] = [];
  const flush = async () => {
    if (batch.length === 0) return;
    const live = await deps.pool.query<{ storage_key: string }>(
      'SELECT storage_key FROM media WHERE storage_key = ANY($1::text[]) AND purged_at IS NULL',
      [batch],
    );
    const keep = new Set(live.rows.map((r) => r.storage_key));
    const orphans = batch.filter((k) => !keep.has(k));
    await deleteObjects(deps.store, orphans);
    stats.orphanObjectsDeleted += orphans.length;
    batch = [];
  };
  for await (const obj of deps.store.list()) {
    if (now - obj.modifiedAt < HOUR_MS) continue;
    batch.push(obj.key);
    if (batch.length >= 500) await flush();
  }
  await flush();

  // 6. Retention.
  stats.sessionsDeleted = (await deps.pool.query('DELETE FROM sessions WHERE expires_at <= $1', [ts(now)])).rowCount ?? 0;
  stats.rateEventsDeleted =
    (await deps.pool.query('DELETE FROM rate_events WHERE occurred_at < $1', [ts(now - 48 * HOUR_MS)])).rowCount ?? 0;
  stats.auditRowsDeleted =
    (await deps.pool.query('DELETE FROM audit_log WHERE created_at < $1', [ts(now - 180 * DAY_MS)])).rowCount ?? 0;
  stats.reportsDeleted =
    (
      await deps.pool.query(`DELETE FROM reports WHERE status <> 'OPEN' AND resolved_at < $1`, [ts(now - 180 * DAY_MS)])
    ).rowCount ?? 0;
  await deps.pool.query('DELETE FROM moderation_actions WHERE created_at < $1', [ts(now - 180 * DAY_MS)]);

  return stats;
}

/** Runs the worker every intervalMs until stop() is called. Errors are logged, never fatal. */
export function startWorkerLoop(
  deps: AppDeps,
  intervalMs: number,
  onError: (err: unknown) => void,
  onRun?: (s: WorkerStats) => void,
): { stop: () => Promise<void> } {
  let running: Promise<void> | null = null;
  let stopped = false;
  const tick = () => {
    if (running || stopped) return;
    running = runWorkerOnce(deps)
      .then((s) => onRun?.(s))
      .catch(onError)
      .finally(() => {
        running = null;
      });
  };
  const timer = setInterval(tick, intervalMs);
  timer.unref();
  tick();
  return {
    stop: async () => {
      stopped = true;
      clearInterval(timer);
      if (running) await running;
    },
  };
}
