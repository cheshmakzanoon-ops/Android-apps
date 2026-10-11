import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import type pg from 'pg';
import { BACKEND_ROOT } from './config.js';
import { sha256Hex } from './lib/crypto.js';

export const MIGRATIONS_DIR = path.join(BACKEND_ROOT, 'migrations');
const FILE_RE = /^(\d{4})_[a-z0-9_]+\.sql$/;

export interface MigrationResult {
  applied: string[];
  skipped: string[];
}

/**
 * Applies plain SQL migrations in file-name order, forward only. Each file runs in its own
 * transaction together with its schema_migrations row (file name and SHA-256 checksum). An
 * applied file whose checksum changed stops the run: history is never rewritten.
 * An advisory lock keeps two instances from migrating at the same time.
 */
export async function migrate(pool: pg.Pool, dir: string = MIGRATIONS_DIR): Promise<MigrationResult> {
  const files = readdirSync(dir)
    .filter((f) => f.endsWith('.sql'))
    .sort();
  for (const f of files) {
    if (!FILE_RE.test(f)) throw new Error(`Migration file name ${f} must look like NNNN_name.sql`);
  }
  const client = await pool.connect();
  const result: MigrationResult = { applied: [], skipped: [] };
  try {
    await client.query('SELECT pg_advisory_lock(hashtextextended($1, 0))', ['photoduel:migrate']);
    await client.query(`CREATE TABLE IF NOT EXISTS schema_migrations (
      filename   text PRIMARY KEY,
      checksum   text NOT NULL,
      applied_at timestamptz NOT NULL DEFAULT now()
    )`);
    const { rows } = await client.query<{ filename: string; checksum: string }>(
      'SELECT filename, checksum FROM schema_migrations',
    );
    const applied = new Map(rows.map((r) => [r.filename, r.checksum]));
    for (const name of applied.keys()) {
      if (!files.includes(name)) throw new Error(`Applied migration ${name} is missing from ${dir}`);
    }
    for (const f of files) {
      const sql = readFileSync(path.join(dir, f), 'utf8');
      const checksum = sha256Hex(sql);
      const prior = applied.get(f);
      if (prior !== undefined) {
        if (prior !== checksum) throw new Error(`Migration ${f} was edited after it was applied (checksum mismatch)`);
        result.skipped.push(f);
        continue;
      }
      await client.query('BEGIN');
      try {
        await client.query(sql);
        await client.query('INSERT INTO schema_migrations (filename, checksum) VALUES ($1, $2)', [f, checksum]);
        await client.query('COMMIT');
      } catch (err) {
        await client.query('ROLLBACK');
        throw new Error(`Migration ${f} failed: ${(err as Error).message}`);
      }
      result.applied.push(f);
    }
  } finally {
    await client.query('SELECT pg_advisory_unlock(hashtextextended($1, 0))', ['photoduel:migrate']).catch(() => {});
    client.release();
  }
  return result;
}
