import type { FastifyInstance } from 'fastify';
import { callerOf } from '../auth.js';
import type { AppDeps } from '../deps.js';
import { ApiError } from '../lib/errors.js';

export const API_VERSION = '0.1.0';

export function metaRoutes(app: FastifyInstance, deps: AppDeps): void {
  app.get('/v1/health', { config: { public: true } }, async () => {
    try {
      await deps.pool.query('SELECT 1');
    } catch {
      throw new ApiError('SERVER', 'Database unavailable', { status: 503 });
    }
    return { status: 'ok', version: API_VERSION, database: 'ok' };
  });

  app.get('/v1/prompts', async (req) => {
    callerOf(req);
    return { version: deps.prompts.version, prompts: deps.prompts.prompts };
  });
}
