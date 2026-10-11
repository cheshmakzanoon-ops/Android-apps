import { productionDeps } from './bootstrap.js';
import { loadConfig } from './config.js';
import { jsonLog } from './lib/logger.js';
import { runWorkerOnce, startWorkerLoop } from './worker.js';

let deps;
try {
  deps = productionDeps(loadConfig());
} catch (err) {
  jsonLog('error', 'worker startup failed', { message: (err as Error).message });
  process.exit(1);
}

if (process.argv.includes('--once')) {
  try {
    const stats = await runWorkerOnce(deps);
    jsonLog('info', 'worker pass complete', { ...stats });
  } catch (err) {
    jsonLog('error', 'worker pass failed', { message: (err as Error).message });
    process.exitCode = 1;
  } finally {
    await deps.pool.end();
  }
} else {
  const loop = startWorkerLoop(
    deps,
    deps.config.workerIntervalMs,
    (err) => jsonLog('error', 'worker pass failed', { message: (err as Error).message }),
    (stats) => jsonLog('info', 'worker pass complete', { ...stats }),
  );
  const keepAlive = setInterval(() => {}, 1 << 30);
  const stop = async () => {
    clearInterval(keepAlive);
    await loop.stop();
    await deps.pool.end();
  };
  process.once('SIGINT', () => void stop());
  process.once('SIGTERM', () => void stop());
}
