/**
 * Deterministic tally, docs/architecture.md section 8. Pure: no I/O, no clock, no randomness.
 * The same rules are implemented by the Kotlin ResultCalculator; both must pass
 * contracts/vectors/results.v1.json.
 */
export type Outcome = 'WINNER' | 'TIE' | 'INSUFFICIENT_SUBMISSIONS' | 'INSUFFICIENT_BALLOTS';
export type SubmissionStatus = 'ACTIVE' | 'WITHDRAWN' | 'REMOVED' | 'HIDDEN';

export interface TallyInput {
  /** Frozen roster at start. */
  roster: readonly string[];
  /** Roster members who are still room members at voting close. */
  activeMembers: readonly string[];
  /** Status as of voting close. author is null when the author deleted the account. */
  submissions: readonly { id: string; author: string | null; status: SubmissionStatus }[];
  /** Unordered pairs; a block in either direction voids a ballot between the two users. */
  blocks: readonly (readonly [string, string])[];
  /** In recording order. voter is null for anonymised ballots of deleted accounts. */
  ballots: readonly { voter: string | null; submission: string }[];
}

export interface TallyResult {
  outcome: Outcome;
  coWinnerSubmissionIds: string[];
  tallies: { submissionId: string; votes: number }[];
  ballotCount: number;
  eligibleVoterCount: number;
}

const byString = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0);

export function tally(input: TallyInput): TallyResult {
  const roster = new Set(input.roster);
  const active = new Set(input.activeMembers.filter((u) => roster.has(u)));
  const eligibleVoterCount = active.size;

  // P: ACTIVE submissions whose author is on the frozen roster.
  const p = new Map<string, string>();
  for (const s of input.submissions) {
    if (s.status === 'ACTIVE' && s.author !== null && roster.has(s.author)) p.set(s.id, s.author);
  }
  const pIds = [...p.keys()].sort(byString);

  if (pIds.length < 2) {
    return { outcome: 'INSUFFICIENT_SUBMISSIONS', coWinnerSubmissionIds: [], tallies: [], ballotCount: 0, eligibleVoterCount };
  }

  const blocked = new Set<string>();
  for (const [a, b] of input.blocks) {
    blocked.add(`${a}\u0000${b}`);
    blocked.add(`${b}\u0000${a}`);
  }

  const votes = new Map<string, number>(pIds.map((id) => [id, 0]));
  const seenVoters = new Set<string>();
  let ballotCount = 0;
  for (const b of input.ballots) {
    if (b.voter === null) continue; // anonymised: no identity, cannot be an eligible voter
    if (seenVoters.has(b.voter)) continue; // ballots are immutable: only the first one counts
    seenVoters.add(b.voter);
    if (!active.has(b.voter)) continue;
    const author = p.get(b.submission);
    if (author === undefined) continue; // target withdrawn, removed, hidden or not in the challenge
    if (author === b.voter) continue; // self-vote is void
    if (blocked.has(`${b.voter}\u0000${author}`)) continue; // blocked pair is void
    votes.set(b.submission, (votes.get(b.submission) ?? 0) + 1);
    ballotCount++;
  }

  const tallies = pIds.map((id) => ({ submissionId: id, votes: votes.get(id) ?? 0 }));
  if (ballotCount < 2) {
    return { outcome: 'INSUFFICIENT_BALLOTS', coWinnerSubmissionIds: [], tallies, ballotCount, eligibleVoterCount };
  }
  const max = Math.max(...tallies.map((t) => t.votes));
  const leaders = tallies.filter((t) => t.votes === max).map((t) => t.submissionId);
  return {
    outcome: leaders.length === 1 ? 'WINNER' : 'TIE',
    coWinnerSubmissionIds: leaders,
    tallies,
    ballotCount,
    eligibleVoterCount,
  };
}
