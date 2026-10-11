import pg from 'pg';

export type Db = pg.Pool;
export type Tx = pg.PoolClient;
export type Queryable = pg.Pool | pg.PoolClient;

const IDENT = /^[a-z_][a-z0-9_]{0,62}$/;

export function createPool(connectionString: string, schema: string, max: number): pg.Pool {
  if (!IDENT.test(schema)) throw new Error('Invalid database schema name');
  const pool = new pg.Pool({
    connectionString,
    max,
    // Every connection resolves unqualified table names in the configured schema only.
    options: `-c search_path=${schema}`,
    application_name: 'photoduel-backend',
  });
  // An idle client error must not crash the process; the pool replaces the client.
  pool.on('error', () => {});
  return pool;
}

/** Runs fn inside BEGIN/COMMIT on one connection; rolls back on any error. */
export async function withTx<T>(pool: pg.Pool, fn: (tx: pg.PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    try {
      await client.query('ROLLBACK');
    } catch {
      // the connection is broken; release(true) below discards it
    }
    throw err;
  } finally {
    client.release();
  }
}

export const ts = (epochMillis: number): Date => new Date(epochMillis);
export const ms = (d: Date | null | undefined): number | null => (d ? d.getTime() : null);

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export const isUuid = (s: unknown): s is string => typeof s === 'string' && UUID_RE.test(s);
