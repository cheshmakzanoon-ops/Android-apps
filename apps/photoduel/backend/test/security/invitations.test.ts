import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { createTestContext, HOUR, type Player, type Res, type TestContext } from '../support/harness.js';
import { expectError, expectStatus, players } from '../support/scenario.js';

let ctx: TestContext;
before(async () => {
  // Capacity 3 makes "full" and "exhausted" (max uses = capacity - 1 = 2) quick to reach.
  ctx = await createTestContext({ ROOM_CAPACITY: '3' });
});
after(async () => {
  await ctx?.close();
});

async function newRoom(owner: Player, name = 'Small room'): Promise<string> {
  const r = await ctx.api.post('/v1/rooms', owner.token, { name });
  expectStatus(r, 201);
  assert.equal(r.json.capacity, 3);
  return r.json.id;
}

async function newInvite(owner: Player, roomId: string, ttlHours = 72): Promise<{ id: string; token: string }> {
  const r = await ctx.api.post(`/v1/rooms/${roomId}/invitations`, owner.token, { ttlHours });
  expectStatus(r, 201);
  assert.match(r.json.token, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(r.json.url, `photoduel://invite/${r.json.token}`);
  return { id: r.json.id, token: r.json.token };
}

const preview = (p: Player, token: string) => ctx.api.get(`/v1/invitations/${token}`, p.token);
const join = (p: Player, token: string) => ctx.api.post(`/v1/invitations/${token}/join`, p.token);

async function activeMembers(roomId: string): Promise<string[]> {
  const { rows } = await ctx.pool.query<{ user_id: string }>(
    'SELECT user_id FROM room_members WHERE room_id = $1 AND left_at IS NULL ORDER BY user_id',
    [roomId],
  );
  return rows.map((r) => r.user_id);
}

async function uses(invitationId: string): Promise<number> {
  return (await ctx.pool.query<{ uses: number }>('SELECT uses FROM invitations WHERE id = $1', [invitationId])).rows[0]!.uses;
}

/** Every unavailable case answers with the identical body, so the cases cannot be told apart. */
let unavailableBody: unknown = null;
function expectUnavailable(r: Res, label: string): void {
  assert.equal(r.status, 404, `${label}: ${r.status} ${JSON.stringify(r.json)}`);
  assert.equal(r.json?.error?.code, 'INVITE_UNAVAILABLE', label);
  if (unavailableBody === null) unavailableBody = r.json;
  assert.deepEqual(r.json, unavailableBody, label);
}

test('an unknown or malformed token returns 404 INVITE_UNAVAILABLE', async () => {
  const [u] = await players(ctx.api, 'Una');
  for (const t of ['A'.repeat(43), 'short', 'x'.repeat(44), '%2e%2e', 'A'.repeat(42) + '!']) {
    expectUnavailable(await preview(u!, encodeURIComponent(t)), `preview ${t}`);
    expectUnavailable(await join(u!, encodeURIComponent(t)), `join ${t}`);
  }
  // Authentication is still required before anything is said about a token.
  expectError(await ctx.api.get(`/v1/invitations/${'A'.repeat(43)}`), 401, 'UNAUTHENTICATED');
});

test('an expired invitation returns 404 INVITE_UNAVAILABLE for preview and join', async () => {
  const [o, j] = await players(ctx.api, 'Own', 'Joe');
  const roomId = await newRoom(o!);
  const inv = await newInvite(o!, roomId, 24);
  expectStatus(await preview(j!, inv.token), 200);
  ctx.clock.advance(24 * HOUR - 1);
  expectStatus(await preview(j!, inv.token), 200);
  ctx.clock.advance(1);
  expectUnavailable(await preview(j!, inv.token), 'expired preview');
  expectUnavailable(await join(j!, inv.token), 'expired join');
  assert.deepEqual(await activeMembers(roomId), [o!.id]);
  assert.equal(await uses(inv.id), 0);
  // Expired invitations are not listed as active.
  const listed = await ctx.api.get(`/v1/rooms/${roomId}/invitations`, o!.token);
  assert.deepEqual(listed.json.invitations, []);
});

test('a revoked invitation returns 404 INVITE_UNAVAILABLE', async () => {
  const [o, m, j] = await players(ctx.api, 'Own', 'Mem', 'Joe');
  const roomId = await newRoom(o!);
  const first = await newInvite(o!, roomId);
  expectStatus(await join(m!, first.token), 200);
  const inv = await newInvite(o!, roomId);
  // Only the creator or the owner may revoke.
  expectError(await ctx.api.del(`/v1/rooms/${roomId}/invitations/${inv.id}`, m!.token), 403, 'FORBIDDEN');
  expectStatus(await ctx.api.del(`/v1/rooms/${roomId}/invitations/${inv.id}`, o!.token), 204);
  expectUnavailable(await preview(j!, inv.token), 'revoked preview');
  expectUnavailable(await join(j!, inv.token), 'revoked join');
  // A revoked invitation does not let an existing member "re-join" either.
  expectUnavailable(await join(m!, inv.token), 'revoked join by a member');
  assert.equal((await activeMembers(roomId)).includes(j!.id), false);
  // Invitations of a deleted room are unavailable too.
  const other = await newInvite(o!, roomId);
  expectStatus(await ctx.api.del(`/v1/rooms/${roomId}`, o!.token), 204);
  expectUnavailable(await preview(j!, other.token), 'deleted room preview');
  expectUnavailable(await join(j!, other.token), 'deleted room join');
});

test('an exhausted invitation returns 404 INVITE_UNAVAILABLE even when the room has space', async () => {
  const [o, a, b, c] = await players(ctx.api, 'Own', 'Ann', 'Bob', 'Cat');
  const roomId = await newRoom(o!);
  const inv = await newInvite(o!, roomId);
  expectStatus(await join(a!, inv.token), 200);
  expectStatus(await join(b!, inv.token), 200);
  assert.equal(await uses(inv.id), 2);
  // Make space again: the invitation is still used up.
  expectStatus(await ctx.api.post(`/v1/rooms/${roomId}/leave`, b!.token, {}), 204);
  assert.equal((await activeMembers(roomId)).length, 2);
  expectUnavailable(await preview(c!, inv.token), 'exhausted preview');
  expectUnavailable(await join(c!, inv.token), 'exhausted join');
  assert.equal(await uses(inv.id), 2);
  // A fresh invitation works.
  const fresh = await newInvite(o!, roomId);
  expectStatus(await join(c!, fresh.token), 200);
});

test('a full room returns 409 ROOM_FULL for join and for new invitations', async () => {
  const [o, a, b, c] = await players(ctx.api, 'Own', 'Ann', 'Bob', 'Cat');
  const roomId = await newRoom(o!);
  const i1 = await newInvite(o!, roomId);
  const i2 = await newInvite(o!, roomId);
  expectStatus(await join(a!, i1.token), 200);
  expectStatus(await join(b!, i1.token), 200);
  // i2 still has uses, but the room is at capacity.
  const full = await join(c!, i2.token);
  expectError(full, 409, 'ROOM_FULL');
  assert.equal(await uses(i2.id), 0, 'a refused join consumes no use');
  expectError(await ctx.api.post(`/v1/rooms/${roomId}/invitations`, o!.token, {}), 409, 'ROOM_FULL');
  assert.equal((await activeMembers(roomId)).length, 3);
});

test('concurrent joins never exceed capacity', async () => {
  const [o, a] = await players(ctx.api, 'Own', 'Ann');
  const roomId = await newRoom(o!);
  const i1 = await newInvite(o!, roomId);
  const i2 = await newInvite(o!, roomId);
  expectStatus(await join(a!, i1.token), 200);
  const racers = await players(ctx.api, 'R1', 'R2', 'R3', 'R4', 'R5', 'R6');
  const results = await Promise.all(racers.map((p, i) => join(p, i % 2 === 0 ? i1.token : i2.token)));
  const ok = results.filter((r) => r.status === 200);
  assert.equal(ok.length, 1, JSON.stringify(results.map((r) => [r.status, r.json?.error?.code])));
  for (const r of results.filter((x) => x.status !== 200)) {
    assert.ok(
      (r.status === 409 && r.json.error.code === 'ROOM_FULL') || (r.status === 404 && r.json.error.code === 'INVITE_UNAVAILABLE'),
      JSON.stringify(r.json),
    );
  }
  assert.equal((await activeMembers(roomId)).length, 3);
  assert.equal((await uses(i1.id)) + (await uses(i2.id)), 2);
});

test('joining is idempotent: repeats and concurrent repeats keep one membership and use one slot', async () => {
  const [o, a, m] = await players(ctx.api, 'Own', 'Ann', 'Max');
  const roomId = await newRoom(o!);
  const inv = await newInvite(o!, roomId);

  const first = await join(a!, inv.token);
  expectStatus(first, 200);
  const again = await join(a!, inv.token);
  expectStatus(again, 200);
  assert.deepEqual(again.json, first.json);
  assert.equal(await uses(inv.id), 1);

  // The owner "joining" their own room is a no-op too.
  expectStatus(await join(o!, inv.token), 200);
  assert.equal(await uses(inv.id), 1);

  const burst = await Promise.all(Array.from({ length: 10 }, () => join(m!, inv.token)));
  for (const r of burst) {
    expectStatus(r, 200);
    assert.equal(r.json.viewerUserId, m!.id);
    assert.equal(r.json.viewerRole, 'MEMBER');
  }
  assert.equal(await uses(inv.id), 2);
  const rows = await ctx.pool.query('SELECT count(*)::int AS n FROM room_members WHERE room_id = $1 AND user_id = $2', [roomId, m!.id]);
  assert.equal(rows.rows[0].n, 1);
  assert.deepEqual(await activeMembers(roomId), [o!.id, a!.id, m!.id].sort());

  // Exhausted and full now, yet a repeat by an existing member is still a 200.
  expectStatus(await join(a!, inv.token), 200);
  expectStatus(await join(m!, inv.token), 200);
});
