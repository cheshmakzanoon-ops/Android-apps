import { loadConfig, requireDatabaseUrl, type Config } from './config.js';
import type { AppDeps } from './deps.js';
import { systemClock } from './lib/clock.js';
import { createPool } from './lib/db.js';
import { LocalDiskStore } from './lib/objectStore.js';
import { loadPromptCatalog } from './lib/prompts.js';

/** Production wiring: environment config, real clock (Date.now), local object store. */
export function productionDeps(config: Config = loadConfig()): AppDeps {
  const prompts = loadPromptCatalog(config.promptsPath);
  const pool = createPool(requireDatabaseUrl(config), config.databaseSchema, config.pgPoolMax);
  return { config, pool, clock: systemClock, store: new LocalDiskStore(config.mediaRoot), prompts };
}
