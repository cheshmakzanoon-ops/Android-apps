import multipart from '@fastify/multipart';
import Fastify, { type FastifyError, type FastifyInstance } from 'fastify';
import { authHook } from './auth.js';
import type { AppDeps } from './deps.js';
import { ApiError } from './lib/errors.js';
import { loggerOptions, redactPath } from './lib/logger.js';
import { challengeRoutes } from './routes/challenges.js';
import { identityRoutes } from './routes/identity.js';
import { metaRoutes } from './routes/meta.js';
import { roomRoutes } from './routes/rooms.js';
import { safetyRoutes } from './routes/safety.js';
import { submissionRoutes } from './routes/submissions.js';

export interface BuildOptions {
  /** Test hook: capture structured logs instead of writing them to stdout. */
  logStream?: NodeJS.WritableStream;
}

function mapFrameworkError(err: FastifyError): ApiError {
  switch (err.code) {
    case 'FST_ERR_CTP_BODY_TOO_LARGE':
    case 'FST_REQ_FILE_TOO_LARGE':
      return new ApiError('PAYLOAD_TOO_LARGE', 'The request body is too large');
    case 'FST_ERR_CTP_INVALID_MEDIA_TYPE':
      return new ApiError('UNSUPPORTED_MEDIA', 'Unsupported Content-Type');
    case 'FST_FILES_LIMIT':
    case 'FST_PARTS_LIMIT':
    case 'FST_FIELDS_LIMIT':
    case 'FST_INVALID_MULTIPART_CONTENT_TYPE':
      return new ApiError('VALIDATION', 'Invalid multipart body: expected exactly the full and thumbnail files');
    default:
      break;
  }
  const status = err.statusCode ?? 500;
  if (status === 413) return new ApiError('PAYLOAD_TOO_LARGE', 'The request body is too large');
  if (status === 415) return new ApiError('UNSUPPORTED_MEDIA', 'Unsupported Content-Type');
  if (status === 404) return new ApiError('NOT_FOUND', 'Not found');
  if (status >= 400 && status < 500) return new ApiError('VALIDATION', 'Invalid request');
  return new ApiError('SERVER', 'Unexpected server error');
}

export async function buildApp(deps: AppDeps, opts: BuildOptions = {}): Promise<FastifyInstance> {
  const app = Fastify({
    logger: loggerOptions(deps.config.logLevel, opts.logStream),
    bodyLimit: deps.config.limits.jsonBodyLimitBytes,
    trustProxy: deps.config.trustProxy,
    disableRequestLogging: false,
    return503OnClosing: true,
    routerOptions: { ignoreTrailingSlash: false, maxParamLength: 128 },
  });

  app.decorateRequest('caller', null);

  await app.register(multipart, {
    limits: {
      fileSize: deps.config.limits.uploadBodyLimitBytes,
      files: 2,
      fields: 0,
      parts: 2,
      headerPairs: 20,
      fieldNameSize: 32,
    },
    throwFileSizeLimit: true,
  });

  app.addHook('onRequest', authHook(deps));

  app.addHook('onSend', async (_req, reply, payload) => {
    if (!reply.hasHeader('cache-control')) reply.header('Cache-Control', 'no-store');
    reply.header('X-Content-Type-Options', 'nosniff');
    return payload;
  });

  app.setErrorHandler((error: FastifyError | ApiError | Error, req, reply) => {
    let apiError: ApiError;
    if (error instanceof ApiError) {
      apiError = error;
    } else if ((error as { code?: string }).code === '23505') {
      // A unique constraint caught a race the locks should have prevented: report a conflict.
      apiError = new ApiError('CONFLICT', 'The request conflicts with the current state');
    } else if ((error as { code?: string }).code === '22P02') {
      apiError = new ApiError('NOT_FOUND', 'Not found');
    } else {
      apiError = mapFrameworkError(error as FastifyError);
    }
    if (apiError.status >= 500) {
      req.log.error(
        { err: { type: error.name, code: (error as { code?: string }).code, message: error.message }, path: redactPath(req.url) },
        'request failed',
      );
    }
    if (apiError.retryAfterSeconds !== undefined) reply.header('Retry-After', String(apiError.retryAfterSeconds));
    void reply.code(apiError.status).send(apiError.body());
  });

  app.setNotFoundHandler((_req, reply) => {
    void reply.code(404).send(new ApiError('NOT_FOUND', 'Not found').body());
  });

  metaRoutes(app, deps);
  identityRoutes(app, deps);
  roomRoutes(app, deps);
  challengeRoutes(app, deps);
  submissionRoutes(app, deps);
  safetyRoutes(app, deps);

  return app;
}
