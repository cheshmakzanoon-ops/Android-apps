import { blockedSet, displayNameOf } from './access.js';
import type { ChallengeRow } from './lifecycle.js';
import { ms, type Queryable } from './lib/db.js';
import type { Outcome } from './lib/tally.js';

export function challengeJson(c: ChallengeRow, now: number) {
  return {
    id: c.id,
    roomId: c.room_id,
    roundNumber: c.round_number,
    prompt: { id: c.prompt_id, title: c.prompt_title, brief: c.prompt_brief },
    state: c.state,
    outcome: c.outcome,
    submissionDeadlineEpochMillis: ms(c.submission_deadline),
    votingDeadlineEpochMillis: ms(c.voting_deadline),
    serverTimeEpochMillis: now,
  };
}

export interface ResultJson {
  outcome: Outcome;
  coWinnerSubmissionIds: string[];
  tallies: { submissionId: string; votes: number }[];
  ballotCount: number;
  eligibleVoterCount: number;
}

export async function resultsFor(q: Queryable, challengeIds: string[]): Promise<Map<string, ResultJson>> {
  const out = new Map<string, ResultJson>();
  if (challengeIds.length === 0) return out;
  const { rows } = await q.query<{
    challenge_id: string;
    outcome: Outcome;
    co_winner_submission_ids: string[];
    tallies: { submissionId: string; votes: number }[];
    ballot_count: number;
    eligible_voter_count: number;
  }>('SELECT * FROM challenge_results WHERE challenge_id = ANY($1::uuid[])', [challengeIds]);
  for (const r of rows) {
    out.set(r.challenge_id, {
      outcome: r.outcome,
      coWinnerSubmissionIds: r.co_winner_submission_ids,
      tallies: r.tallies,
      ballotCount: r.ballot_count,
      eligibleVoterCount: r.eligible_voter_count,
    });
  }
  return out;
}

export async function roomDetailJson(q: Queryable, roomId: string, viewerId: string) {
  const room = await q.query<{ id: string; name: string; capacity: number; owner_id: string }>(
    'SELECT id, name, capacity, owner_id FROM rooms WHERE id = $1 AND deleted_at IS NULL',
    [roomId],
  );
  const r = room.rows[0];
  if (!r) return null;
  const members = await q.query<{ user_id: string; role: 'OWNER' | 'MEMBER'; display_name: string | null; deleted_at: Date | null }>(
    `SELECT m.user_id, m.role, u.display_name, u.deleted_at FROM room_members m JOIN users u ON u.id = m.user_id
      WHERE m.room_id = $1 AND m.left_at IS NULL ORDER BY (m.role = 'OWNER') DESC, m.joined_at, m.user_id`,
    [roomId],
  );
  const viewer = members.rows.find((m) => m.user_id === viewerId);
  if (!viewer) return null;
  return {
    id: r.id,
    name: r.name,
    capacity: r.capacity,
    viewerUserId: viewerId,
    viewerRole: viewer.role,
    members: members.rows.map((m) => ({
      userId: m.user_id,
      displayName: displayNameOf(m.display_name, m.deleted_at),
      role: m.role,
    })),
  };
}

interface SubmissionRow {
  id: string;
  challenge_id: string;
  author_id: string | null;
  status: 'ACTIVE' | 'WITHDRAWN' | 'REMOVED' | 'HIDDEN';
  full_media_id: string;
  thumb_media_id: string;
  width: number;
  height: number;
  display_name: string | null;
  deleted_at: Date | null;
}

export function submissionJson(s: SubmissionRow, viewerId: string) {
  return {
    id: s.id,
    challengeId: s.challenge_id,
    authorUserId: s.author_id,
    authorDisplayName: displayNameOf(s.display_name, s.author_id === null ? new Date(0) : s.deleted_at),
    status: s.status,
    thumbnailMediaId: s.thumb_media_id,
    fullMediaId: s.full_media_id,
    width: s.width,
    height: s.height,
    isMine: s.author_id !== null && s.author_id === viewerId,
  };
}

export async function submissionById(q: Queryable, id: string, viewerId: string) {
  const { rows } = await q.query<SubmissionRow>(
    `SELECT s.*, u.display_name, u.deleted_at FROM submissions s LEFT JOIN users u ON u.id = s.author_id WHERE s.id = $1`,
    [id],
  );
  return rows[0] ? submissionJson(rows[0], viewerId) : null;
}

/**
 * ChallengeView for a viewer who is already known to be an active member of the room.
 * Submission visibility follows api-contract.md section 6.
 */
export async function challengeViewJson(q: Queryable, c: ChallengeRow, viewerId: string, ownerId: string, now: number) {
  const started = c.started_at !== null;
  const rosterRows = started
    ? await q.query<{ user_id: string; display_name: string | null; deleted_at: Date | null }>(
        `SELECT r.user_id, u.display_name, u.deleted_at FROM roster r JOIN users u ON u.id = r.user_id
          WHERE r.challenge_id = $1 ORDER BY (r.user_id = $2) DESC, r.user_id`,
        [c.id, ownerId],
      )
    : c.state === 'CREATED'
      ? await q.query<{ user_id: string; display_name: string | null; deleted_at: Date | null }>(
          `SELECT m.user_id, u.display_name, u.deleted_at FROM room_members m JOIN users u ON u.id = m.user_id
            WHERE m.room_id = $1 AND m.left_at IS NULL ORDER BY (m.user_id = $2) DESC, m.joined_at, m.user_id`,
          [c.room_id, ownerId],
        )
      : { rows: [] };
  const roster = rosterRows.rows.map((r) => ({
    userId: r.user_id,
    displayName: displayNameOf(r.display_name, r.deleted_at),
    role: r.user_id === ownerId ? ('OWNER' as const) : ('MEMBER' as const),
  }));
  const isRosterMember = roster.some((r) => r.userId === viewerId);

  const all = await q.query<SubmissionRow>(
    `SELECT s.*, u.display_name, u.deleted_at FROM submissions s LEFT JOIN users u ON u.id = s.author_id
      WHERE s.challenge_id = $1 AND s.status IN ('ACTIVE', 'HIDDEN') ORDER BY s.id`,
    [c.id],
  );
  const mine = all.rows.find((s) => s.author_id === viewerId) ?? null;
  let visible: SubmissionRow[] = [];
  if (c.state === 'OPEN_FOR_SUBMISSIONS') {
    visible = mine ? [mine] : [];
  } else if (c.state === 'VOTING' || c.state === 'COMPLETED' || c.state === 'ARCHIVED') {
    const blocked = await blockedSet(q, viewerId);
    visible = all.rows.filter(
      (s) =>
        s.author_id === viewerId ||
        (s.status === 'ACTIVE' && s.author_id !== null && !blocked.has(s.author_id)),
    );
  }

  const ballot = await q.query<{ submission_id: string }>(
    'SELECT submission_id FROM votes WHERE challenge_id = $1 AND voter_id = $2',
    [c.id, viewerId],
  );
  const myBallot = ballot.rows[0]?.submission_id ?? null;
  const canSubmit =
    isRosterMember && c.state === 'OPEN_FOR_SUBMISSIONS' && now < (ms(c.submission_deadline) ?? 0);
  const canVote =
    isRosterMember && c.state === 'VOTING' && now < (ms(c.voting_deadline) ?? 0) && myBallot === null;

  const results = c.state === 'COMPLETED' || c.state === 'ARCHIVED' ? await resultsFor(q, [c.id]) : new Map();
  return {
    challenge: challengeJson(c, now),
    roster,
    submissions: visible.map((s) => submissionJson(s, viewerId)),
    myStatus: {
      isRosterMember,
      mySubmissionId: mine?.id ?? null,
      myBallotSubmissionId: myBallot,
      canSubmit,
      canVote,
    },
    result: results.get(c.id) ?? null,
  };
}

export function encodeCursor(parts: (string | number)[]): string {
  return Buffer.from(JSON.stringify(parts)).toString('base64url');
}

export function decodeCursor(raw: string | undefined): unknown[] | null {
  if (raw === undefined || raw === '') return null;
  try {
    const v = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'));
    return Array.isArray(v) ? v : null;
  } catch {
    return null;
  }
}
