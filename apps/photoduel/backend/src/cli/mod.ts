import { randomUUID } from 'node:crypto';
import { realpathSync } from 'node:fs';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import type pg from 'pg';
import { softDeleteRoom } from '../accountDeletion.js';
import { loadConfig, requireDatabaseUrl } from '../config.js';
import { deleteObjects } from '../lifecycle.js';
import { audit } from '../lib/audit.js';
import type { Clock } from '../lib/clock.js';
import { systemClock } from '../lib/clock.js';
import { createPool, isUuid, ts, withTx, type Tx } from '../lib/db.js';
import { LocalDiskStore, type ObjectStore } from '../lib/objectStore.js';

/**
 * Moderation CLI. The only moderation surface: it needs direct database access and there is no
 * HTTP admin route or admin credential. Every subcommand, including the read-only ones, writes an
 * audit_log row naming the operator.
 *
 *   npm run mod -- list-reports [--status OPEN|DISMISSED|ACTIONED|ALL]
 *   npm run mod -- show-report <id>
 *   npm run mod -- hide-submission <id> --reason "<text>"
 *   npm run mod -- restore-submission <id>
 *   npm run mod -- dismiss-report <id>
 *   npm run mod -- suspend-user <id> --reason "<text>"
 *   npm run mod -- restore-user <id>
 *   npm run mod -- purge-room <id>
 *
 * Options: --operator <name> (default: $MOD_OPERATOR, then the OS user name).
 */
export interface ModContext {
  pool: pg.Pool;
  store: ObjectStore;
  clock: Clock;
  out: (line: string) => void;
  err: (line: string) => void;
  env?: NodeJS.ProcessEnv;
}

export const USAGE = `usage: npm run mod -- <subcommand> [args] [--operator <name>]
  list-reports [--status OPEN|DISMISSED|ACTIONED|ALL]
  show-report <id>
  hide-submission <id> --reason "<text>"
  restore-submission <id>
  dismiss-report <id>
  suspend-user <id> --reason "<text>"
  restore-user <id>
  purge-room <id>`;

class UsageError extends Error {}

function parseArgs(argv: string[]): { positional: string[]; flags: Map<string, string> } {
  const positional: string[] = [];
  const flags = new Map<string, string>();
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    if (a.startsWith('--')) {
      const name = a.slice(2);
      const value = argv[i + 1];
      if (value === undefined || value.startsWith('--')) throw new UsageError(`--${name} needs a value`);
      flags.set(name, value);
      i++;
    } else {
      positional.push(a);
    }
  }
  return { positional, flags };
}

async function record(tx: Tx, now: number, operator: string, action: string, targetType: string, targetId: string, reason: string | null, details: Record<string, unknown> = {}) {
  await tx.query(
    `INSERT INTO moderation_actions (id, operator, action, target_type, target_id, reason, created_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7)`,
    [randomUUID(), operator, action, targetType, targetId, reason, ts(now)],
  );
  await audit(tx, now, `operator:${operator}`, action, targetType, targetId, reason === null ? details : { ...details, reason });
}

export async function runMod(argv: string[], ctx: ModContext): Promise<number> {
  let parsed;
  try {
    parsed = parseArgs(argv);
  } catch (e) {
    ctx.err((e as Error).message);
    ctx.err(USAGE);
    return 2;
  }
  const { positional, flags } = parsed;
  const [cmd, id] = positional;
  const env = ctx.env ?? process.env;
  const operator = (flags.get('operator') ?? env.MOD_OPERATOR ?? os.userInfo().username ?? '').trim();
  if (!cmd) {
    ctx.err(USAGE);
    return 2;
  }
  if (!/^[\w.@-]{1,64}$/.test(operator)) {
    ctx.err('An operator name is required (--operator or MOD_OPERATOR)');
    return 2;
  }
  const needsId = cmd !== 'list-reports';
  if (needsId && !isUuid(id)) {
    ctx.err(`${cmd} needs an id`);
    ctx.err(USAGE);
    return 2;
  }
  const reason = flags.get('reason')?.trim() ?? null;
  if ((cmd === 'hide-submission' || cmd === 'suspend-user') && !reason) {
    ctx.err(`${cmd} needs --reason "<text>"`);
    return 2;
  }
  const now = ctx.clock.now();
  const target = (id ?? '').toLowerCase();

  try {
    switch (cmd) {
      case 'list-reports': {
        const status = (flags.get('status') ?? 'OPEN').toUpperCase();
        if (!['OPEN', 'DISMISSED', 'ACTIONED', 'ALL'].includes(status)) throw new UsageError('invalid --status');
        const rows = await withTx(ctx.pool, async (tx) => {
          const r = await tx.query(
            `SELECT r.id, r.reporter_id, r.target_type, r.target_id, r.reason, r.details, r.status, r.created_at,
                    (SELECT count(DISTINCT x.reporter_id) FROM reports x
                      WHERE x.target_type = r.target_type AND x.target_id = r.target_id AND x.status = 'OPEN') AS open_reporters
               FROM reports r WHERE ($1 = 'ALL' OR r.status = $1) ORDER BY r.created_at, r.id`,
            [status],
          );
          await record(tx, now, operator, 'LIST_REPORTS', 'REPORT', '*', null, { status, count: r.rowCount });
          return r.rows;
        });
        for (const r of rows) ctx.out(JSON.stringify(r));
        return 0;
      }
      case 'show-report': {
        const out = await withTx(ctx.pool, async (tx) => {
          const r = await tx.query('SELECT * FROM reports WHERE id = $1', [target]);
          const rep = r.rows[0];
          if (!rep) return null;
          let subject: unknown = null;
          if (rep.target_type === 'SUBMISSION') {
            const s = await tx.query(
              `SELECT s.id, s.status, s.author_id, s.challenge_id, c.room_id, c.state AS challenge_state, s.hidden_reason
                 FROM submissions s JOIN challenges c ON c.id = s.challenge_id WHERE s.id = $1`,
              [rep.target_id],
            );
            subject = s.rows[0] ?? null;
          } else {
            const u = await tx.query('SELECT id, display_name, suspended_at, deleted_at FROM users WHERE id = $1', [rep.target_id]);
            subject = u.rows[0] ?? null;
          }
          await record(tx, now, operator, 'SHOW_REPORT', 'REPORT', target, null);
          return { report: rep, target: subject };
        });
        if (!out) throw new UsageError('report not found');
        ctx.out(JSON.stringify(out));
        return 0;
      }
      case 'hide-submission':
      case 'restore-submission': {
        const hide = cmd === 'hide-submission';
        await withTx(ctx.pool, async (tx) => {
          const s = await tx.query<{ status: string }>('SELECT status FROM submissions WHERE id = $1 FOR UPDATE', [target]);
          if (!s.rows[0]) throw new UsageError('submission not found');
          if (hide) {
            if (s.rows[0].status !== 'ACTIVE' && s.rows[0].status !== 'HIDDEN') throw new UsageError(`submission is ${s.rows[0].status}`);
            await tx.query(
              `UPDATE submissions SET status = 'HIDDEN', hidden_at = $2, hidden_reason = $3 WHERE id = $1`,
              [target, ts(now), `MODERATOR: ${reason}`],
            );
            await tx.query(
              `UPDATE reports SET status = 'ACTIONED', resolved_at = $2, resolved_by = $3
                WHERE target_type = 'SUBMISSION' AND target_id = $1 AND status = 'OPEN'`,
              [target, ts(now), operator],
            );
          } else {
            if (s.rows[0].status !== 'HIDDEN') throw new UsageError(`submission is ${s.rows[0].status}, not HIDDEN`);
            const media = await tx.query(
              `SELECT 1 FROM submissions s JOIN media m ON m.id IN (s.full_media_id, s.thumb_media_id)
                WHERE s.id = $1 AND m.purged_at IS NOT NULL`,
              [target],
            );
            if ((media.rowCount ?? 0) > 0) throw new UsageError('submission media was purged; it cannot be restored');
            await tx.query(`UPDATE submissions SET status = 'ACTIVE', hidden_at = NULL, hidden_reason = NULL WHERE id = $1`, [target]);
            await tx.query(
              `UPDATE reports SET status = 'DISMISSED', resolved_at = $2, resolved_by = $3
                WHERE target_type = 'SUBMISSION' AND target_id = $1 AND status = 'OPEN'`,
              [target, ts(now), operator],
            );
          }
          await record(tx, now, operator, hide ? 'HIDE_SUBMISSION' : 'RESTORE_SUBMISSION', 'SUBMISSION', target, reason);
        });
        ctx.out(JSON.stringify({ ok: true, action: cmd, id: target }));
        return 0;
      }
      case 'dismiss-report': {
        await withTx(ctx.pool, async (tx) => {
          const r = await tx.query(
            `UPDATE reports SET status = 'DISMISSED', resolved_at = $2, resolved_by = $3 WHERE id = $1 AND status = 'OPEN'`,
            [target, ts(now), operator],
          );
          if ((r.rowCount ?? 0) === 0) throw new UsageError('open report not found');
          await record(tx, now, operator, 'DISMISS_REPORT', 'REPORT', target, reason);
        });
        ctx.out(JSON.stringify({ ok: true, action: cmd, id: target }));
        return 0;
      }
      case 'suspend-user':
      case 'restore-user': {
        const suspend = cmd === 'suspend-user';
        await withTx(ctx.pool, async (tx) => {
          const r = suspend
            ? await tx.query('UPDATE users SET suspended_at = $2, suspended_reason = $3 WHERE id = $1 AND deleted_at IS NULL', [
                target,
                ts(now),
                reason,
              ])
            : await tx.query('UPDATE users SET suspended_at = NULL, suspended_reason = NULL WHERE id = $1 AND deleted_at IS NULL', [
                target,
              ]);
          if ((r.rowCount ?? 0) === 0) throw new UsageError('user not found');
          await record(tx, now, operator, suspend ? 'SUSPEND_USER' : 'RESTORE_USER', 'USER', target, reason);
        });
        ctx.out(JSON.stringify({ ok: true, action: cmd, id: target }));
        return 0;
      }
      case 'purge-room': {
        const keys = await withTx(ctx.pool, async (tx) => {
          const r = await tx.query('SELECT id FROM rooms WHERE id = $1 FOR UPDATE', [target]);
          if (!r.rows[0]) throw new UsageError('room not found');
          const k = await softDeleteRoom(tx, target, now);
          await record(tx, now, operator, 'PURGE_ROOM', 'ROOM', target, reason, { objects: k.length });
          return k;
        });
        await deleteObjects(ctx.store, keys);
        ctx.out(JSON.stringify({ ok: true, action: cmd, id: target, objectsDeleted: keys.length }));
        return 0;
      }
      default:
        ctx.err(`unknown subcommand: ${cmd}`);
        ctx.err(USAGE);
        return 2;
    }
  } catch (e) {
    if (e instanceof UsageError) {
      ctx.err(e.message);
      return 1;
    }
    ctx.err(`failed: ${(e as Error).message}`);
    return 1;
  }
}

function isMain(): boolean {
  const entry = process.argv[1];
  if (!entry) return false;
  try {
    return realpathSync(entry) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
}

if (isMain()) {
  const config = loadConfig();
  let pool: pg.Pool | null = null;
  try {
    pool = createPool(requireDatabaseUrl(config), config.databaseSchema, 2);
    process.exitCode = await runMod(process.argv.slice(2), {
      pool,
      store: new LocalDiskStore(config.mediaRoot),
      clock: systemClock,
      out: (l) => process.stdout.write(l + '\n'),
      err: (l) => process.stderr.write(l + '\n'),
    });
  } catch (e) {
    process.stderr.write(`failed: ${(e as Error).message}\n`);
    process.exitCode = 1;
  } finally {
    await pool?.end();
  }
}
