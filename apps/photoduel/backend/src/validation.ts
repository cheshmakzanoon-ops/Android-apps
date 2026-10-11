import { z } from 'zod';
import { ApiError } from './lib/errors.js';
import { isUuid } from './lib/db.js';

/** Parses a JSON body with a strict schema. Unknown fields are rejected with VALIDATION. */
export function parseBody<T extends z.ZodType>(schema: T, body: unknown): z.infer<T> {
  const r = schema.safeParse(body ?? {});
  if (!r.success) {
    const issue = r.error.issues[0];
    const field = issue && issue.path.length > 0 ? issue.path.join('.') : undefined;
    let message: string;
    if (issue?.code === 'unrecognized_keys') {
      message = `Unknown field: ${(issue as { keys?: string[] }).keys?.join(', ') ?? ''}`.trim();
    } else {
      message = field ? `Invalid field ${field}: ${issue?.message ?? 'invalid'}` : issue?.message ?? 'Invalid request body';
    }
    throw new ApiError('VALIDATION', message);
  }
  return r.data;
}

/** For routes that take no payload: absent body or {} only. */
export const EmptyBody = z.strictObject({});

/** 1–24 characters after trimming: letters, digits, space and -_. (no emoji or control characters). */
export const DisplayName = z
  .string()
  .max(200)
  .transform((s) => s.trim().normalize('NFC'))
  .refine((s) => [...s].length >= 1 && [...s].length <= 24, 'must be 1 to 24 characters')
  .refine((s) => /^[\p{L}\p{M}\p{Nd} _.\-]+$/u.test(s), 'may contain letters, digits, space and - _ . only');

export const RoomName = z
  .string()
  .max(400)
  .transform((s) => s.trim().normalize('NFC'))
  .refine((s) => [...s].length >= 1 && [...s].length <= 40, 'must be 1 to 40 characters')
  .refine((s) => !/[\p{Cc}\p{Cf}\p{Co}\p{Cs}]/u.test(s), 'must not contain control characters');

export const Uuid = z.string().refine(isUuid, 'must be an id');

/** Path ids that are not UUIDs cannot exist, so they are reported as NOT_FOUND, never as a 400. */
export function pathId(value: unknown, notFoundCode: 'NOT_FOUND' = 'NOT_FOUND'): string {
  if (!isUuid(value)) throw new ApiError(notFoundCode, 'Not found');
  return (value as string).toLowerCase();
}

export function pageLimit(raw: unknown): number {
  if (raw === undefined) return 20;
  if (typeof raw !== 'string' || !/^\d{1,3}$/.test(raw)) throw new ApiError('VALIDATION', 'Invalid field limit');
  const n = Number(raw);
  if (n < 1 || n > 50) throw new ApiError('VALIDATION', 'Invalid field limit: must be 1 to 50');
  return n;
}

export function strictQuery(query: unknown, allowed: string[]): Record<string, string | undefined> {
  const q = (query ?? {}) as Record<string, unknown>;
  const out: Record<string, string | undefined> = {};
  for (const [k, v] of Object.entries(q)) {
    if (!allowed.includes(k)) throw new ApiError('VALIDATION', `Unknown query parameter: ${k}`);
    if (typeof v !== 'string') throw new ApiError('VALIDATION', `Invalid query parameter: ${k}`);
    out[k] = v;
  }
  return out;
}
