import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { runMod } from '../../src/cli/mod.js';
import {
  guest,
  roomWith,
  startedChallenge,
  submitPhoto,
  type Api,
  type Player,
  type Res,
  type TestContext,
} from './harness.js';

/** Asserts an error response: HTTP status, error code, and the contract's error body shape. */
export function expectError(res: Res, status: number, code: string): void {
  assert.equal(res.status, status, `expected ${status} ${code}, got ${res.status} ${JSON.stringify(res.json)}`);
  assert.equal(res.json?.error?.code, code, `expected code ${code}, got ${JSON.stringify(res.json)}`);
  assert.equal(typeof res.json.error.message, 'string');
}

export function expectStatus(res: Res, status: number): void {
  assert.equal(res.status, status, `expected ${status}, got ${res.status} ${JSON.stringify(res.json)}`);
}

export async function players(api: Api, ...names: string[]): Promise<Player[]> {
  const out: Player[] = [];
  for (const n of names) out.push(await guest(api, n));
  return out;
}

export async function viewChallenge(api: Api, p: Player, challengeId: string): Promise<any> {
  const r = await api.get(`/v1/challenges/${challengeId}`, p.token);
  expectStatus(r, 200);
  return r.json;
}

export interface Round {
  roomId: string;
  challengeId: string;
  /** Submission JSON by author user id, as returned by the upload. */
  subs: Map<string, any>;
}

/**
 * Owner plus members in a new room, a started challenge (1 h submission, 1 h voting) and one photo
 * from each author. Returns before the submission deadline.
 */
export async function openRound(ctx: TestContext, owner: Player, members: Player[], authors: Player[]): Promise<Round> {
  const roomId = await roomWith(ctx.api, owner, members);
  const challengeId = await startedChallenge(ctx.api, owner, roomId, 1, 1);
  const subs = new Map<string, any>();
  const colors = ['#aa2200', '#22aa00', '#0022aa', '#aaaa00', '#00aaaa', '#aa00aa', '#555555', '#999999'];
  for (const [i, a] of authors.entries()) {
    const r = await submitPhoto(ctx.api, a, challengeId, colors[i % colors.length]);
    expectStatus(r, 201);
    subs.set(a.id, r.json);
  }
  return { roomId, challengeId, subs };
}

export async function deadlines(ctx: TestContext, challengeId: string): Promise<{ submission: number; voting: number }> {
  const { rows } = await ctx.pool.query<{ submission_deadline: Date; voting_deadline: Date }>(
    'SELECT submission_deadline, voting_deadline FROM challenges WHERE id = $1',
    [challengeId],
  );
  const r = rows[0]!;
  return { submission: r.submission_deadline.getTime(), voting: r.voting_deadline.getTime() };
}

/** Moves the clock to the submission deadline and lets a member read the challenge (lazy transition). */
export async function toVoting(ctx: TestContext, viewer: Player, round: Round): Promise<any> {
  ctx.clock.set((await deadlines(ctx, round.challengeId)).submission);
  const v = await viewChallenge(ctx.api, viewer, round.challengeId);
  assert.equal(v.challenge.state, 'VOTING');
  return v;
}

export async function toCompleted(ctx: TestContext, viewer: Player, round: Round): Promise<any> {
  ctx.clock.set((await deadlines(ctx, round.challengeId)).voting);
  const v = await viewChallenge(ctx.api, viewer, round.challengeId);
  assert.equal(v.challenge.state, 'COMPLETED');
  return v;
}

export async function vote(api: Api, voter: Player, challengeId: string, submissionId: string): Promise<Res> {
  return api.post(`/v1/challenges/${challengeId}/ballot`, voter.token, { submissionId });
}

/** Every regular file below a directory (the local object store). */
export function listFiles(root: string): string[] {
  const out: string[] = [];
  const walk = (dir: string) => {
    let entries: string[];
    try {
      entries = readdirSync(dir);
    } catch {
      return;
    }
    for (const e of entries) {
      const full = path.join(dir, e);
      const st = statSync(full);
      if (st.isDirectory()) walk(full);
      else out.push(path.relative(root, full));
    }
  };
  walk(root);
  return out.sort();
}

/** The bytes the object store holds for a media row, read straight from disk. */
export async function storedMedia(ctx: TestContext, mediaId: string): Promise<{ bytes: Buffer | null; sha256: string; purged: boolean }> {
  const { rows } = await ctx.pool.query<{ storage_key: string; sha256: string; purged_at: Date | null }>(
    'SELECT storage_key, sha256, purged_at FROM media WHERE id = $1',
    [mediaId],
  );
  const m = rows[0];
  assert.ok(m, `media row ${mediaId} must exist`);
  let bytes: Buffer | null = null;
  try {
    bytes = readFileSync(path.join(ctx.mediaRoot, ...m.storage_key.split('/')));
  } catch {
    bytes = null;
  }
  return { bytes, sha256: m.sha256, purged: m.purged_at !== null };
}

export interface ModRun {
  code: number;
  out: string[];
  err: string[];
}

/** Runs the moderation CLI in-process against the test schema, store and clock. */
export async function mod(ctx: TestContext, ...argv: string[]): Promise<ModRun> {
  const out: string[] = [];
  const err: string[] = [];
  const code = await runMod(argv, {
    pool: ctx.pool,
    store: ctx.deps.store,
    clock: ctx.clock,
    out: (l) => out.push(l),
    err: (l) => err.push(l),
    env: {},
  });
  return { code, out, err };
}

export async function auditRows(ctx: TestContext, action?: string): Promise<any[]> {
  const { rows } = action
    ? await ctx.pool.query('SELECT * FROM audit_log WHERE action = $1 ORDER BY id', [action])
    : await ctx.pool.query('SELECT * FROM audit_log ORDER BY id');
  return rows;
}
