import { randomBytes } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Writable } from 'node:stream';
import type { FastifyInstance } from 'fastify';
import pg from 'pg';
import sharp from 'sharp';
import { buildApp } from '../../src/app.js';
import { loadConfig, type Config } from '../../src/config.js';
import type { AppDeps } from '../../src/deps.js';
import { ManualClock } from '../../src/lib/clock.js';
import { createPool } from '../../src/lib/db.js';
import { LocalDiskStore } from '../../src/lib/objectStore.js';
import { loadPromptCatalog } from '../../src/lib/prompts.js';
import { migrate } from '../../src/migrate.js';

/**
 * Tests talk only to TEST_DATABASE_URL. Each test file gets its own schema (created, migrated
 * and dropped here), its own media directory and its own manual clock. No other database is
 * ever touched, and DATABASE_URL is deliberately ignored.
 */
export function testDatabaseUrl(): string {
  const url = process.env.TEST_DATABASE_URL;
  if (!url) {
    throw new Error(
      'TEST_DATABASE_URL is not set. Backend tests need a real PostgreSQL 16 database; ' +
        'see scripts/local-postgres.sh for a local cluster.',
    );
  }
  return url;
}

export interface TestContext {
  app: FastifyInstance;
  deps: AppDeps;
  pool: pg.Pool;
  clock: ManualClock;
  config: Config;
  schema: string;
  mediaRoot: string;
  logs: string[];
  api: Api;
  close(): Promise<void>;
}

export async function createTestContext(env: Record<string, string> = {}): Promise<TestContext> {
  const url = testDatabaseUrl();
  const schema = `pd_test_${randomBytes(6).toString('hex')}`;
  const admin = new pg.Client({ connectionString: url });
  await admin.connect();
  await admin.query(`CREATE SCHEMA ${schema}`);
  await admin.end();

  const mediaRoot = mkdtempSync(path.join(os.tmpdir(), 'photoduel-media-'));
  const config = loadConfig({
    NODE_ENV: 'test',
    LOG_LEVEL: 'info',
    DATABASE_SCHEMA: schema,
    MEDIA_ROOT: mediaRoot,
    WORKER_IN_PROCESS: 'false',
    ...env,
  });
  const pool = createPool(url, schema, 12);
  await migrate(pool);
  // Start at real time (minute-aligned) so object-store mtimes and the clock agree.
  const clock = new ManualClock(Math.floor(Date.now() / 60_000) * 60_000);
  const deps: AppDeps = {
    config,
    pool,
    clock,
    store: new LocalDiskStore(mediaRoot),
    prompts: loadPromptCatalog(config.promptsPath),
  };
  const logs: string[] = [];
  const logStream = new Writable({
    write(chunk, _enc, cb) {
      logs.push(chunk.toString());
      cb();
    },
  });
  const app = await buildApp(deps, { logStream });
  await app.ready();
  const ctx: TestContext = {
    app,
    deps,
    pool,
    clock,
    config,
    schema,
    mediaRoot,
    logs,
    api: new Api(app),
    async close() {
      await app.close();
      await pool.end();
      const c = new pg.Client({ connectionString: url });
      await c.connect();
      await c.query(`DROP SCHEMA ${schema} CASCADE`);
      await c.end();
      rmSync(mediaRoot, { recursive: true, force: true });
    },
  };
  return ctx;
}

export interface Res {
  status: number;
  json: any;
  headers: Record<string, string | string[] | number | undefined>;
  raw: Buffer;
}

let ipCounter = 1;
/** A distinct client address per guest, so the per-IP guest quota does not interfere. */
export function nextIp(): string {
  ipCounter++;
  return `10.${(ipCounter >> 16) & 255}.${(ipCounter >> 8) & 255}.${ipCounter & 255}`;
}

export class Api {
  constructor(private readonly app: FastifyInstance) {}

  async call(
    method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    url: string,
    opts: { token?: string; body?: unknown; payload?: Buffer | string; headers?: Record<string, string>; ip?: string } = {},
  ): Promise<Res> {
    const headers: Record<string, string> = { ...(opts.headers ?? {}) };
    if (opts.token) headers.authorization = `Bearer ${opts.token}`;
    let payload: Buffer | string | undefined = opts.payload;
    if (opts.body !== undefined) {
      payload = JSON.stringify(opts.body);
      headers['content-type'] = 'application/json';
    }
    const res = await this.app.inject({
      method,
      url,
      headers,
      ...(payload !== undefined ? { payload } : {}),
      remoteAddress: opts.ip ?? '127.0.0.1',
    });
    let json: any = null;
    const ct = String(res.headers['content-type'] ?? '');
    if (ct.includes('application/json') && res.body.length > 0) json = JSON.parse(res.body);
    return { status: res.statusCode, json, headers: res.headers, raw: res.rawPayload };
  }

  get(url: string, token?: string) {
    return this.call('GET', url, token ? { token } : {});
  }
  post(url: string, token: string | undefined, body: unknown = {}) {
    return this.call('POST', url, { ...(token ? { token } : {}), body });
  }
  put(url: string, token: string | undefined, body?: unknown) {
    return this.call('PUT', url, { ...(token ? { token } : {}), ...(body !== undefined ? { body } : {}) });
  }
  patch(url: string, token: string | undefined, body: unknown) {
    return this.call('PATCH', url, { ...(token ? { token } : {}), body });
  }
  del(url: string, token: string | undefined, body?: unknown) {
    return this.call('DELETE', url, { ...(token ? { token } : {}), ...(body !== undefined ? { body } : {}) });
  }
}

export interface Player {
  id: string;
  token: string;
  name: string;
}

export async function guest(api: Api, name: string): Promise<Player> {
  const r = await api.call('POST', '/v1/identity/guest', { body: { displayName: name }, ip: nextIp() });
  if (r.status !== 201) throw new Error(`guest creation failed: ${r.status} ${JSON.stringify(r.json)}`);
  return { id: r.json.user.id, token: r.json.session.token, name };
}

export async function invite(api: Api, owner: Player, roomId: string): Promise<string> {
  const r = await api.post(`/v1/rooms/${roomId}/invitations`, owner.token, { ttlHours: 72 });
  if (r.status !== 201) throw new Error(`invite failed: ${r.status} ${JSON.stringify(r.json)}`);
  return r.json.token;
}

/** Owner plus members, joined through one invitation. Returns the room id. */
export async function roomWith(api: Api, owner: Player, members: Player[], name = 'Test room'): Promise<string> {
  const r = await api.post('/v1/rooms', owner.token, { name });
  if (r.status !== 201) throw new Error(`room creation failed: ${r.status} ${JSON.stringify(r.json)}`);
  const roomId = r.json.id as string;
  if (members.length > 0) {
    const token = await invite(api, owner, roomId);
    for (const m of members) {
      const j = await api.post(`/v1/invitations/${token}/join`, m.token);
      if (j.status !== 200) throw new Error(`join failed: ${j.status} ${JSON.stringify(j.json)}`);
    }
  }
  return roomId;
}

export async function createChallenge(api: Api, owner: Player, roomId: string, submissionHours = 24, votingHours = 6): Promise<string> {
  const r = await api.post(`/v1/rooms/${roomId}/challenges`, owner.token, { promptId: 'p-001', submissionHours, votingHours });
  if (r.status !== 201) throw new Error(`challenge creation failed: ${r.status} ${JSON.stringify(r.json)}`);
  return r.json.id;
}

export async function startedChallenge(api: Api, owner: Player, roomId: string, submissionHours = 24, votingHours = 6): Promise<string> {
  const id = await createChallenge(api, owner, roomId, submissionHours, votingHours);
  const s = await api.post(`/v1/challenges/${id}/start`, owner.token);
  if (s.status !== 200) throw new Error(`start failed: ${s.status} ${JSON.stringify(s.json)}`);
  return id;
}

// ---- Images and multipart ---------------------------------------------------------------------

export async function jpeg(width: number, height: number, color = '#3366cc'): Promise<Buffer> {
  return sharp({ create: { width, height, channels: 3, background: color } }).jpeg({ quality: 85 }).toBuffer();
}
export async function png(width: number, height: number, color = '#cc6633'): Promise<Buffer> {
  return sharp({ create: { width, height, channels: 3, background: color } }).png().toBuffer();
}
export async function webp(width: number, height: number, color = '#33cc66'): Promise<Buffer> {
  return sharp({ create: { width, height, channels: 3, background: color } }).webp().toBuffer();
}

export const GPS_MARKER = 'PHOTODUEL-SECRET-LOCATION';

/**
 * Builds a big-endian TIFF/EXIF APP1 segment with an ImageDescription marker and a GPS IFD
 * (latitude 51 deg N, longitude 0 deg W), then inserts it right after the JPEG SOI marker.
 */
export function withGpsExif(jpegBytes: Buffer): Buffer {
  const desc = Buffer.from(GPS_MARKER + '\0', 'latin1');
  // Layout (offsets from TIFF header start):
  // 0: header (8) | 8: IFD0 (2 + 2*12 + 4 = 30) | 38: GPS IFD (2 + 4*12 + 4 = 54) | 92: data
  const ifd0Off = 8;
  const gpsOff = ifd0Off + 2 + 2 * 12 + 4;
  const dataOff = gpsOff + 2 + 4 * 12 + 4;
  const latOff = dataOff;
  const lonOff = latOff + 24;
  const descOff = lonOff + 24;
  const tiff = Buffer.alloc(descOff + desc.length);
  tiff.write('MM', 0, 'latin1');
  tiff.writeUInt16BE(42, 2);
  tiff.writeUInt32BE(ifd0Off, 4);
  let p = ifd0Off;
  tiff.writeUInt16BE(2, p); p += 2;
  // ImageDescription (0x010e), ASCII
  tiff.writeUInt16BE(0x010e, p); tiff.writeUInt16BE(2, p + 2); tiff.writeUInt32BE(desc.length, p + 4); tiff.writeUInt32BE(descOff, p + 8); p += 12;
  // GPSInfo IFD pointer (0x8825), LONG
  tiff.writeUInt16BE(0x8825, p); tiff.writeUInt16BE(4, p + 2); tiff.writeUInt32BE(1, p + 4); tiff.writeUInt32BE(gpsOff, p + 8); p += 12;
  tiff.writeUInt32BE(0, p);
  p = gpsOff;
  tiff.writeUInt16BE(4, p); p += 2;
  // GPSLatitudeRef 'N'
  tiff.writeUInt16BE(0x0001, p); tiff.writeUInt16BE(2, p + 2); tiff.writeUInt32BE(2, p + 4); tiff.write('N\0', p + 8, 'latin1'); p += 12;
  // GPSLatitude 51/1 28/1 0/1
  tiff.writeUInt16BE(0x0002, p); tiff.writeUInt16BE(5, p + 2); tiff.writeUInt32BE(3, p + 4); tiff.writeUInt32BE(latOff, p + 8); p += 12;
  // GPSLongitudeRef 'W'
  tiff.writeUInt16BE(0x0003, p); tiff.writeUInt16BE(2, p + 2); tiff.writeUInt32BE(2, p + 4); tiff.write('W\0', p + 8, 'latin1'); p += 12;
  // GPSLongitude 0/1 7/1 0/1
  tiff.writeUInt16BE(0x0004, p); tiff.writeUInt16BE(5, p + 2); tiff.writeUInt32BE(3, p + 4); tiff.writeUInt32BE(lonOff, p + 8); p += 12;
  tiff.writeUInt32BE(0, p);
  const rationals = (off: number, vals: number[]) => vals.forEach((v, i) => { tiff.writeUInt32BE(v, off + i * 8); tiff.writeUInt32BE(1, off + i * 8 + 4); });
  rationals(latOff, [51, 28, 0]);
  rationals(lonOff, [0, 7, 0]);
  desc.copy(tiff, descOff);
  const body = Buffer.concat([Buffer.from('Exif\0\0', 'latin1'), tiff]);
  const seg = Buffer.alloc(4);
  seg.writeUInt16BE(0xffe1, 0);
  seg.writeUInt16BE(body.length + 2, 2);
  return Buffer.concat([jpegBytes.subarray(0, 2), seg, body, jpegBytes.subarray(2)]);
}

/** True when the JPEG contains an APP1 (EXIF/XMP) segment before the image data. */
export function hasApp1(jpegBytes: Buffer): boolean {
  let i = 2;
  while (i + 4 <= jpegBytes.length && jpegBytes[i] === 0xff) {
    const marker = jpegBytes[i + 1]!;
    if (marker === 0xda) return false; // start of scan: no more metadata segments
    if (marker === 0xe1) return true;
    const len = jpegBytes.readUInt16BE(i + 2);
    i += 2 + len;
  }
  return false;
}

export interface Part {
  name: string;
  data: Buffer;
  filename?: string;
  contentType?: string;
}

export function multipart(parts: Part[]): { payload: Buffer; headers: Record<string, string> } {
  const boundary = `----photoduel${randomBytes(8).toString('hex')}`;
  const chunks: Buffer[] = [];
  for (const p of parts) {
    const disp = p.filename === undefined
      ? `form-data; name="${p.name}"`
      : `form-data; name="${p.name}"; filename="${p.filename}"`;
    const head = `--${boundary}\r\nContent-Disposition: ${disp}\r\n` +
      (p.contentType ? `Content-Type: ${p.contentType}\r\n` : '') + '\r\n';
    chunks.push(Buffer.from(head, 'latin1'), p.data, Buffer.from('\r\n', 'latin1'));
  }
  chunks.push(Buffer.from(`--${boundary}--\r\n`, 'latin1'));
  return { payload: Buffer.concat(chunks), headers: { 'content-type': `multipart/form-data; boundary=${boundary}` } };
}

export function photoParts(full: Buffer, thumbnail: Buffer): Part[] {
  return [
    { name: 'full', data: full, filename: 'full.jpg', contentType: 'image/jpeg' },
    { name: 'thumbnail', data: thumbnail, filename: 'thumb.jpg', contentType: 'image/jpeg' },
  ];
}

export async function upload(api: Api, player: Player, challengeId: string, parts: Part[]): Promise<Res> {
  const mp = multipart(parts);
  return api.call('PUT', `/v1/challenges/${challengeId}/submission`, { token: player.token, payload: mp.payload, headers: mp.headers });
}

export async function submitPhoto(api: Api, player: Player, challengeId: string, color = '#3366cc'): Promise<Res> {
  const full = await jpeg(800, 600, color);
  const thumb = await jpeg(480, 360, color);
  return upload(api, player, challengeId, photoParts(full, thumb));
}

export const HOUR = 3600_000;
