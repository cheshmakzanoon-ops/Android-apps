import { loadConfig, requireDatabaseUrl } from './config.js';
import { createPool } from './lib/db.js';
import { jsonLog } from './lib/logger.js';
import { migrate } from './migrate.js';

const config = loadConfig();
const pool = createPool(requireDatabaseUrl(config), config.databaseSchema, 2);
try {
  if (config.databaseSchema !== 'public') {
    await pool.query(`CREATE SCHEMA IF NOT EXISTS ${config.databaseSchema}`);
  }
  const r = await migrate(pool);
  jsonLog('info', 'migrations complete', { applied: r.applied, alreadyApplied: r.skipped.length });
} catch (err) {
  jsonLog('error', 'migration failed', { message: (err as Error).message });
  process.exitCode = 1;
} finally {
  await pool.end();
}
