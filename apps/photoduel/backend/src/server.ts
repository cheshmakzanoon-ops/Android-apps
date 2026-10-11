import { buildApp } from './app.js';
import { productionDeps } from './bootstrap.js';
import { loadConfig } from './config.js';
import { jsonLog } from './lib/logger.js';
import { startWorkerLoop } from './worker.js';

let deps;
try {
  deps = productionDeps(loadConfig());
} catch (err) {
  jsonLog('error', 'startup failed', { message: (err as Error).message });
  process.exit(1);
}
const app = await buildApp(deps);
const worker = deps.config.workerInProcess
  ? startWorkerLoop(deps, deps.config.workerIntervalMs, (err) =>
      app.log.error({ err: { type: (err as Error).name, message: (err as Error).message } }, 'worker pass failed'),
    )
  : null;

const shutdown = async (signal: string) => {
  app.log.info({ signal }, 'shutting down');
  await worker?.stop();
  await app.close();
  await deps.pool.end();
  process.exit(0);
};
process.once('SIGINT', () => void shutdown('SIGINT'));
process.once('SIGTERM', () => void shutdown('SIGTERM'));

await app.listen({ host: deps.config.host, port: deps.config.port });
