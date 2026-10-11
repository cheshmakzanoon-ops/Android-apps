import type { FastifyServerOptions } from 'fastify';

/**
 * Structured JSON logging. Request logs carry method, a redacted path, status and timing only:
 * no headers (so no bearer tokens), no bodies (so no photo bytes or EXIF data), and invitation
 * tokens in paths are replaced with a placeholder.
 */
export function redactPath(url: string | undefined): string {
  if (!url) return '';
  const pathOnly = url.split('?')[0] ?? '';
  return pathOnly.replace(/\/v1\/invitations\/[^/]+/g, '/v1/invitations/[redacted]');
}

export function loggerOptions(level: string, stream?: NodeJS.WritableStream): FastifyServerOptions['logger'] {
  const base = {
    level,
    base: { service: 'photoduel-api' },
    messageKey: 'msg',
    serializers: {
      req(req: { method?: string; url?: string; id?: string }) {
        return { method: req.method, path: redactPath(req.url), reqId: req.id };
      },
      res(res: { statusCode?: number }) {
        return { statusCode: res.statusCode };
      },
      err(err: { name?: string; code?: string; message?: string }) {
        // No stack, no cause chain: only the type, code and message.
        return { type: err.name ?? 'Error', code: err.code, message: err.message ?? '', stack: '' };
      },
    },
    redact: {
      paths: ['req.headers', 'headers', 'authorization', '*.authorization', 'token', '*.token', 'body', '*.body'],
      censor: '[redacted]',
    },
  };
  return (stream ? { ...base, stream } : base) as FastifyServerOptions['logger'];
}

/** Minimal JSON line logger for the CLI and worker processes. */
export function jsonLog(level: 'info' | 'warn' | 'error', msg: string, fields: Record<string, unknown> = {}): void {
  const line = JSON.stringify({ level, time: Date.now(), service: 'photoduel-worker', msg, ...fields });
  if (level === 'error') process.stderr.write(line + '\n');
  else process.stdout.write(line + '\n');
}
