import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';

/** Directory of the backend project (the folder that holds package.json). */
export const BACKEND_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

const intFromEnv = (def: number, min: number, max: number) =>
  z
    .string()
    .optional()
    .transform((v, ctx) => {
      if (v === undefined || v.trim() === '') return def;
      if (!/^-?\d+$/.test(v.trim())) {
        ctx.addIssue({ code: 'custom', message: 'must be an integer' });
        return z.NEVER;
      }
      return Number.parseInt(v.trim(), 10);
    })
    .pipe(z.number().int().min(min).max(max));

const boolFromEnv = (def: boolean) =>
  z
    .string()
    .optional()
    .transform((v, ctx) => {
      if (v === undefined || v.trim() === '') return def;
      const s = v.trim().toLowerCase();
      if (s === 'true' || s === '1') return true;
      if (s === 'false' || s === '0') return false;
      ctx.addIssue({ code: 'custom', message: 'must be true or false' });
      return z.NEVER;
    });

const schemaName = z
  .string()
  .regex(/^[a-z_][a-z0-9_]{0,62}$/, 'must be a lowercase SQL identifier');

const EnvSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  HOST: z.string().min(1).default('127.0.0.1'),
  PORT: intFromEnv(8787, 0, 65535),
  DATABASE_URL: z.string().min(1).optional(),
  DATABASE_SCHEMA: schemaName.default('public'),
  PG_POOL_MAX: intFromEnv(10, 1, 200),
  MEDIA_ROOT: z.string().min(1).default('var/media'),
  PROMPTS_PATH: z.string().min(1).default('../assets/source/prompts/prompts.v1.json'),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
  TRUST_PROXY: boolFromEnv(false),
  IP_HASH_SALT: z.string().optional(),
  RECOVERY_CODE_PEPPER: z.string().optional(),
  RECOVERY_SCRYPT_N: intFromEnv(16384, 1024, 1048576),
  WORKER_IN_PROCESS: boolFromEnv(true),
  WORKER_INTERVAL_MS: intFromEnv(30000, 1000, 3600000),

  SESSION_TTL_DAYS: intFromEnv(90, 1, 365),
  GUESTS_PER_IP_PER_HOUR: intFromEnv(10, 1, 100000),
  RESTORE_FAILURES_PER_IP_PER_HOUR: intFromEnv(5, 1, 100000),
  ROOMS_PER_USER_PER_DAY: intFromEnv(5, 1, 100000),
  ACTIVE_INVITATIONS_PER_ROOM: intFromEnv(5, 1, 100),
  ROOM_CAPACITY: intFromEnv(8, 2, 8),
  MIN_MEMBERS_TO_START: intFromEnv(3, 2, 8),
  UPLOADS_PER_USER_PER_DAY: intFromEnv(30, 1, 100000),
  UPLOAD_BYTES_PER_USER_PER_DAY: intFromEnv(60 * 1024 * 1024, 1024, 10 * 1024 * 1024 * 1024),
  UPLOAD_BODY_LIMIT_BYTES: intFromEnv(3 * 1024 * 1024, 1024, 50 * 1024 * 1024),
  JSON_BODY_LIMIT_BYTES: intFromEnv(16 * 1024, 256, 1024 * 1024),
  BALLOTS_PER_USER_PER_MINUTE: intFromEnv(60, 1, 100000),
  REPORTS_PER_USER_PER_DAY: intFromEnv(20, 1, 100000),
  AUTO_HIDE_REPORTERS: intFromEnv(3, 1, 100),
  MAX_INPUT_PIXELS: intFromEnv(40_000_000, 10_000, 200_000_000),
  MIN_SHORT_EDGE_PX: intFromEnv(200, 1, 10000),
});

export interface Config {
  nodeEnv: 'development' | 'test' | 'production';
  host: string;
  port: number;
  databaseUrl: string | undefined;
  databaseSchema: string;
  pgPoolMax: number;
  mediaRoot: string;
  promptsPath: string;
  logLevel: string;
  trustProxy: boolean;
  ipHashSalt: string;
  recoveryCodePepper: string;
  recoveryScryptN: number;
  workerInProcess: boolean;
  workerIntervalMs: number;
  sessionTtlMs: number;
  limits: {
    guestsPerIpPerHour: number;
    restoreFailuresPerIpPerHour: number;
    roomsPerUserPerDay: number;
    activeInvitationsPerRoom: number;
    roomCapacity: number;
    minMembersToStart: number;
    uploadsPerUserPerDay: number;
    uploadBytesPerUserPerDay: number;
    uploadBodyLimitBytes: number;
    jsonBodyLimitBytes: number;
    ballotsPerUserPerMinute: number;
    reportsPerUserPerDay: number;
    autoHideReporters: number;
    maxInputPixels: number;
    minShortEdgePx: number;
  };
}

const DEV_SALT = 'photoduel-development-only-salt';

export class ConfigError extends Error {}

/**
 * Reads and validates every setting. Defaults are documented in .env.example. Fails fast with a
 * message naming each invalid variable; it never prints the values, which may be secrets.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = EnvSchema.safeParse(env);
  if (!parsed.success) {
    const fields = parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ');
    throw new ConfigError(`Invalid configuration: ${fields}`);
  }
  const e = parsed.data;
  if (e.NODE_ENV === 'production') {
    if (!e.IP_HASH_SALT || e.IP_HASH_SALT.length < 16) {
      throw new ConfigError('Invalid configuration: IP_HASH_SALT must be set (16+ characters) in production');
    }
    if (!e.RECOVERY_CODE_PEPPER || e.RECOVERY_CODE_PEPPER.length < 16) {
      throw new ConfigError('Invalid configuration: RECOVERY_CODE_PEPPER must be set (16+ characters) in production');
    }
  }
  if (e.MIN_MEMBERS_TO_START > e.ROOM_CAPACITY) {
    throw new ConfigError('Invalid configuration: MIN_MEMBERS_TO_START exceeds ROOM_CAPACITY');
  }
  return {
    nodeEnv: e.NODE_ENV,
    host: e.HOST,
    port: e.PORT,
    databaseUrl: e.DATABASE_URL,
    databaseSchema: e.DATABASE_SCHEMA,
    pgPoolMax: e.PG_POOL_MAX,
    mediaRoot: path.resolve(BACKEND_ROOT, e.MEDIA_ROOT),
    promptsPath: path.resolve(BACKEND_ROOT, e.PROMPTS_PATH),
    logLevel: e.LOG_LEVEL,
    trustProxy: e.TRUST_PROXY,
    ipHashSalt: e.IP_HASH_SALT ?? DEV_SALT,
    recoveryCodePepper: e.RECOVERY_CODE_PEPPER ?? DEV_SALT,
    recoveryScryptN: e.RECOVERY_SCRYPT_N,
    workerInProcess: e.WORKER_IN_PROCESS,
    workerIntervalMs: e.WORKER_INTERVAL_MS,
    sessionTtlMs: e.SESSION_TTL_DAYS * 24 * 3600 * 1000,
    limits: {
      guestsPerIpPerHour: e.GUESTS_PER_IP_PER_HOUR,
      restoreFailuresPerIpPerHour: e.RESTORE_FAILURES_PER_IP_PER_HOUR,
      roomsPerUserPerDay: e.ROOMS_PER_USER_PER_DAY,
      activeInvitationsPerRoom: e.ACTIVE_INVITATIONS_PER_ROOM,
      roomCapacity: e.ROOM_CAPACITY,
      minMembersToStart: e.MIN_MEMBERS_TO_START,
      uploadsPerUserPerDay: e.UPLOADS_PER_USER_PER_DAY,
      uploadBytesPerUserPerDay: e.UPLOAD_BYTES_PER_USER_PER_DAY,
      uploadBodyLimitBytes: e.UPLOAD_BODY_LIMIT_BYTES,
      jsonBodyLimitBytes: e.JSON_BODY_LIMIT_BYTES,
      ballotsPerUserPerMinute: e.BALLOTS_PER_USER_PER_MINUTE,
      reportsPerUserPerDay: e.REPORTS_PER_USER_PER_DAY,
      autoHideReporters: e.AUTO_HIDE_REPORTERS,
      maxInputPixels: e.MAX_INPUT_PIXELS,
      minShortEdgePx: e.MIN_SHORT_EDGE_PX,
    },
  };
}

export function requireDatabaseUrl(config: Config): string {
  if (!config.databaseUrl) throw new ConfigError('Invalid configuration: DATABASE_URL is required');
  return config.databaseUrl;
}
