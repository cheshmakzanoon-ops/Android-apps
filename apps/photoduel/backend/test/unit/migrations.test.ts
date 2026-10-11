import assert from 'node:assert/strict';
import { cpSync, mkdtempSync, readdirSync, rmSync, appendFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';
import pg from 'pg';
import { createPool } from '../../src/lib/db.js';
import { migrate, MIGRATIONS_DIR } from '../../src/migrate.js';
import { testDatabaseUrl } from '../support/harness.js';

const url = testDatabaseUrl();
const schema = `pd_test_mig_${process.pid}`;
let pool: pg.Pool;

before(async () => {
  const c = new pg.Client({ connectionString: url });
  await c.connect();
  await c.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
  await c.query(`CREATE SCHEMA ${schema}`);
  await c.end();
  pool = createPool(url, schema, 2);
});

after(async () => {
  await pool.end();
  const c = new pg.Client({ connectionString: url });
  await c.connect();
  await c.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
  await c.end();
});

test('migrations apply in order, record checksums and are idempotent', async () => {
  const first = await migrate(pool);
  const files = readdirSync(MIGRATIONS_DIR).filter((f) => f.endsWith('.sql')).sort();
  assert.deepEqual(first.applied, files);
  const { rows } = await pool.query('SELECT filename, checksum FROM schema_migrations ORDER BY filename');
  assert.deepEqual(rows.map((r) => r.filename), files);
  for (const r of rows) assert.match(r.checksum, /^[0-9a-f]{64}$/);
  const second = await migrate(pool);
  assert.deepEqual(second.applied, []);
  assert.deepEqual(second.skipped, files);
  // Every table from the schema exists in the isolated schema.
  const t = await pool.query(`SELECT table_name FROM information_schema.tables WHERE table_schema = $1`, [schema]);
  const names = new Set(t.rows.map((r) => r.table_name));
  for (const n of ['users', 'sessions', 'recovery_codes', 'rooms', 'room_members', 'invitations', 'challenges', 'roster',
    'submissions', 'media', 'votes', 'challenge_results', 'reports', 'blocks', 'moderation_actions', 'audit_log', 'rate_events']) {
    assert.ok(names.has(n), `missing table ${n}`);
  }
});

test('an applied migration that was edited is refused (forward-only, checksummed)', async () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'photoduel-mig-'));
  try {
    cpSync(MIGRATIONS_DIR, dir, { recursive: true });
    const firstFile = readdirSync(dir).filter((f) => f.endsWith('.sql')).sort()[0]!;
    appendFileSync(path.join(dir, firstFile), '\n-- edited\n');
    await assert.rejects(migrate(pool, dir), /checksum mismatch/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('votes cannot hold two ballots for one live voter (database constraint)', async () => {
  const c = await pool.query(
    `SELECT conname FROM pg_constraint WHERE conname = 'votes_one_per_voter' AND contype = 'u'`,
  );
  assert.equal(c.rowCount, 1);
  const pk = await pool.query(
    `SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
      WHERE i.indrelid = 'challenge_results'::regclass AND i.indisprimary`,
  );
  assert.deepEqual(pk.rows.map((r) => r.attname), ['challenge_id']);
});
