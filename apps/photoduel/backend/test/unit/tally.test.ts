import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { BACKEND_ROOT } from '../../src/config.js';
import { tally, type TallyInput } from '../../src/lib/tally.js';

const vectorsPath = path.resolve(BACKEND_ROOT, '..', 'contracts', 'vectors', 'results.v1.json');
const vectors = JSON.parse(readFileSync(vectorsPath, 'utf8')) as {
  ruleVersion: string;
  cases: (TallyInput & { name: string; blocks: [string, string][]; expected: Record<string, unknown> })[];
};

test('golden vector file is present and non-empty', () => {
  assert.equal(vectors.ruleVersion, '1');
  assert.ok(vectors.cases.length >= 9, `expected at least 9 cases, found ${vectors.cases.length}`);
});

for (const c of vectors.cases) {
  test(`tally vector: ${c.name}`, () => {
    const got = tally(c);
    const { winnerSubmissionId, ...expected } = c.expected as Record<string, unknown> & { winnerSubmissionId: string | null };
    assert.deepEqual(got, expected);
    // winnerSubmissionId in the vectors is the single winner for WINNER, else null.
    assert.equal(got.outcome === 'WINNER' ? got.coWinnerSubmissionIds[0] : null, winnerSubmissionId);
  });
}

test('tally ignores anonymised ballots and ballots from members who left', () => {
  const r = tally({
    roster: ['a', 'b', 'c', 'd'],
    activeMembers: ['a', 'b', 'c'],
    submissions: [
      { id: 's1', author: 'a', status: 'ACTIVE' },
      { id: 's2', author: 'b', status: 'ACTIVE' },
    ],
    blocks: [],
    ballots: [
      { voter: null, submission: 's1' },
      { voter: 'd', submission: 's1' },
      { voter: 'c', submission: 's2' },
      { voter: 'a', submission: 's2' },
    ],
  });
  assert.equal(r.outcome, 'WINNER');
  assert.deepEqual(r.coWinnerSubmissionIds, ['s2']);
  assert.equal(r.ballotCount, 2);
  assert.equal(r.eligibleVoterCount, 3);
});

test('tally is deterministic regardless of submission order', () => {
  const base: TallyInput = {
    roster: ['a', 'b', 'c'],
    activeMembers: ['a', 'b', 'c'],
    submissions: [
      { id: 'z', author: 'a', status: 'ACTIVE' },
      { id: 'm', author: 'b', status: 'ACTIVE' },
      { id: 'b', author: 'c', status: 'ACTIVE' },
    ],
    blocks: [],
    ballots: [
      { voter: 'a', submission: 'm' },
      { voter: 'b', submission: 'z' },
      { voter: 'c', submission: 'z' },
    ],
  };
  const r1 = tally(base);
  const r2 = tally({ ...base, submissions: [...base.submissions].reverse() });
  assert.deepEqual(r1, r2);
  assert.deepEqual(r1.tallies.map((t) => t.submissionId), ['b', 'm', 'z']);
});
