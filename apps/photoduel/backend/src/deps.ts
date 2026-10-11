import type pg from 'pg';
import type { Config } from './config.js';
import type { Clock } from './lib/clock.js';
import type { ObjectStore } from './lib/objectStore.js';
import type { PromptCatalog } from './lib/prompts.js';

/** Everything a request handler, the worker or the moderation CLI needs. Injected, never global. */
export interface AppDeps {
  config: Config;
  pool: pg.Pool;
  clock: Clock;
  store: ObjectStore;
  prompts: PromptCatalog;
}

/** Authenticated caller, resolved per request from the bearer token. */
export interface Caller {
  userId: string;
  displayName: string;
  tokenHash: Buffer;
}

declare module 'fastify' {
  interface FastifyRequest {
    caller: Caller | null;
  }
  interface FastifyContextConfig {
    public?: boolean;
  }
}
