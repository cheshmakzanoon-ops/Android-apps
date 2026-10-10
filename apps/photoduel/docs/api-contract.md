# PhotoDuel HTTP API contract v1

Base path: `/v1`. JSON request and response bodies, UTF-8. Timestamps are integer **epoch milliseconds** (UTC). Ids are opaque strings (UUID v4 text). The server's wall clock is the only time source for rules.

## Common rules

- **Authentication:** `Authorization: Bearer <session-token>` on every route except `POST /v1/identity/guest`, `POST /v1/identity/restore` and `GET /v1/health`.
- **Errors:** every non-2xx response has the body `{"error":{"code":"<CODE>","message":"<human readable>","retryAfterSeconds":<int optional>}}`. Codes are listed in §3.
- **Server time:** every response carries a `Date` header (RFC 7231). Challenge responses also carry `serverTimeEpochMillis`. The client uses these to compute its offset.
- **Idempotency:** `POST /v1/challenges/{id}/ballot` and `PUT /v1/challenges/{id}/submission` are idempotent for the same input. Other POSTs are not retried automatically by the client.
- **Body limits:** JSON bodies 16 KB maximum. The photo upload body is 3 MB maximum.
- **Unknown fields** in request bodies are rejected with `VALIDATION`.
- **Privacy in errors:** a resource the caller may not see returns `404 NOT_FOUND`, never `403`, so membership cannot be probed. `403 FORBIDDEN` is returned only where the caller already knows the resource exists (for example, a non-owner trying to start a challenge).

## 1. Health

| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/v1/health` | none | `200 {"status":"ok","version":"<semver>","database":"ok"}` |

## 2. Identity

### POST `/v1/identity/guest`
Request: `{"displayName":"Ada"}` (1–24 characters after trimming; letters, digits, space, and `-_.`; no emoji or control characters).
Response `201`: `{"user":{"id":"…","displayName":"Ada","hasRecoveryCode":false},"session":{"token":"…","expiresAtEpochMillis":1234}}`.
Rate limit: 10 per hour per IP hash (`RATE_LIMITED`).

### POST `/v1/identity/restore`
Request: `{"recoveryCode":"ABCD-EFGH-JKMN-PQRS"}` (Crockford base32, case-insensitive, hyphens optional).
Response `200`: same shape as guest creation. A new session is issued; **the recovery code is rotated** and the response includes `"recoveryCode":"<new code>"` once.
Invalid code: `401 UNAUTHENTICATED` with code `INVALID_RECOVERY_CODE` in the message. Five failed attempts per IP hash per hour return `429`.

### POST `/v1/identity/recovery-code` (auth)
Response `201`: `{"recoveryCode":"ABCD-EFGH-JKMN-PQRS"}`. Shown once. Replaces any earlier code.

### POST `/v1/identity/logout` (auth) → `204`
### GET `/v1/me` (auth) → `200 {"id","displayName","hasRecoveryCode"}`
### PATCH `/v1/me` (auth) → body `{"displayName"}` → `200` profile.
### DELETE `/v1/me` (auth) → body `{"confirm":"DELETE"}` → `204`.
Effects: session revoked, all photos purged, memberships removed, display name cleared (shown as "Deleted player" in history), ballots kept with voter id set to null. Repeating the call after completion returns `401`.

## 3. Error codes

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `VALIDATION` | Body or query failed validation. `message` says which field |
| 401 | `UNAUTHENTICATED` | Missing, invalid, expired or revoked session, or a suspended account |
| 403 | `FORBIDDEN` | Caller is known to the resource but not allowed (for example, a non-owner starting a challenge) |
| 404 | `NOT_FOUND` | Resource does not exist **or** the caller may not see it |
| 404 | `INVITE_UNAVAILABLE` | Invitation token is unknown, revoked, expired, exhausted or its room is deleted. The same code for all cases |
| 409 | `CONFLICT` | Generic state conflict (for example, room already has an active challenge) |
| 409 | `INVALID_TRANSITION` | Challenge state transition not allowed |
| 409 | `ALREADY_VOTED` | Ballot exists for a different submission |
| 409 | `ROSTER_FROZEN` | Membership change blocked during an active challenge |
| 409 | `ROOM_FULL` | Room capacity reached |
| 410 | `DEADLINE_PASSED` | Submission or ballot after the server-side deadline |
| 413 | `PAYLOAD_TOO_LARGE` | Upload over 3 MB or JSON over 16 KB |
| 415 | `UNSUPPORTED_MEDIA` | Not JPEG, PNG or WebP by magic bytes, or image exceeds 40 MP, or short edge under 200 px |
| 422 | `INVALID_BALLOT` | Self-vote, withdrawn/removed/hidden target, blocked pair, or target not in the challenge |
| 429 | `RATE_LIMITED` | Over quota. `retryAfterSeconds` is set |
| 500 | `SERVER` | Unexpected failure. Logged without sensitive data |

## 4. Rooms and invitations

### GET `/v1/rooms` (auth) → `200 {"rooms":[RoomSummary]}`
`RoomSummary`: `{"id","name","memberCount","capacity","activeChallengeState":"OPEN_FOR_SUBMISSIONS"|null}`. Only rooms the caller belongs to.

### POST `/v1/rooms` (auth)
Request: `{"name":"Sunday walkers"}` (1–40 characters after trimming). Quota: 5 per day (`RATE_LIMITED`).
Response `201`: `RoomDetail`.

### GET `/v1/rooms/{roomId}` (auth) → `200 RoomDetail`, or `404`
`RoomDetail`:
```json
{
  "id": "…", "name": "Sunday walkers", "capacity": 8,
  "viewerUserId": "…", "viewerRole": "OWNER",
  "members": [{"userId":"…","displayName":"Ada","role":"OWNER"}]
}
```

### POST `/v1/rooms/{roomId}/leave` (auth) → `204`
Blocked with `409 ROSTER_FROZEN` while a challenge is `OPEN_FOR_SUBMISSIONS` or `VOTING`. The owner cannot leave; the owner deletes the room instead.

### DELETE `/v1/rooms/{roomId}/members/{userId}` (owner) → `204`
Same `ROSTER_FROZEN` rule. Cannot remove the owner.

### DELETE `/v1/rooms/{roomId}` (owner) → `204`
Hides the room at once. Photos purged within 24 hours. Rows removed after 7 days. Owner-only: `403 FORBIDDEN` for other members.

### POST `/v1/rooms/{roomId}/invitations` (member) → `201`
Request: `{"ttlHours":72}`, one of `24`, `72` or `168`. Default `72`.
Response: `{"id":"…","token":"<43-char base64url>","expiresAtEpochMillis":…,"url":"photoduel://invite/<token>"}`. The token is returned **once**. The server stores only its SHA-256 hash.
Limits: 5 active invitations per room (`CONFLICT`); the room must not be full.

### GET `/v1/rooms/{roomId}/invitations` (member) → `200 {"invitations":[{"id","expiresAtEpochMillis","usesRemaining","createdByUserId"}]}`
Never includes tokens.

### DELETE `/v1/rooms/{roomId}/invitations/{invitationId}` (member) → `204`
Any member may revoke an invitation they created; the owner may revoke any invitation.

### GET `/v1/invitations/{token}` (auth) → `200 InvitationPreview`, or `404 INVITE_UNAVAILABLE`
`InvitationPreview`: `{"roomName","ownerDisplayName","memberCount","capacity","expiresAtEpochMillis"}`.

### POST `/v1/invitations/{token}/join` (auth) → `200 RoomDetail`
- Idempotent for an existing member: returns the room with `200`.
- Otherwise: `409 ROOM_FULL` when at capacity; `404 INVITE_UNAVAILABLE` when invalid.
- Joining is allowed during an active challenge. The new member can see the room but is **not** on the frozen roster, so they can neither submit nor vote in that challenge.
- Usage counter increments atomically under a row lock.

## 5. Prompts

### GET `/v1/prompts` (auth) → `200 {"version":"1.0.0","prompts":[{"id":"p-001","title":"…","brief":"…"}]}`
The catalogue is loaded from `assets/source/prompts/prompts.v1.json`. It contains 30 curated prompts. Users cannot write their own public prompts in this release.

## 6. Challenges

### POST `/v1/rooms/{roomId}/challenges` (owner) → `201 Challenge`
Request: `{"promptId":"p-001","submissionHours":24,"votingHours":6}`.
- `submissionHours` ∈ {1, 6, 24, 48, 72}. `votingHours` ∈ {1, 6, 24}.
- Room must have no challenge in `CREATED`, `OPEN_FOR_SUBMISSIONS` or `VOTING` (`CONFLICT`).
- Creates the challenge in `CREATED`. Deadlines are `null` until started.

`Challenge`:
```json
{
  "id":"…","roomId":"…","roundNumber":2,
  "prompt":{"id":"p-001","title":"…","brief":"…"},
  "state":"OPEN_FOR_SUBMISSIONS","outcome":null,
  "submissionDeadlineEpochMillis":1234,"votingDeadlineEpochMillis":5678,
  "serverTimeEpochMillis":1200
}
```

### POST `/v1/challenges/{challengeId}/start` (owner) → `200 Challenge`
- Requires `CREATED` (else `409 INVALID_TRANSITION`) and at least 3 members (`409 CONFLICT`, message "At least 3 members are needed").
- Freezes the roster. Sets `submissionDeadline = now + submissionHours` and `votingDeadline = submissionDeadline + votingHours`.

### POST `/v1/challenges/{challengeId}/cancel` (owner) → `200 Challenge`
Allowed from `CREATED` or `OPEN_FOR_SUBMISSIONS`. Otherwise `409 INVALID_TRANSITION`. Photos are purged within 24 hours.

### GET `/v1/challenges/{challengeId}` (member) → `200 ChallengeView`
```json
{
  "challenge": { "...Challenge..." },
  "roster": [{"userId":"…","displayName":"Ada","role":"OWNER"}],
  "submissions": [ { "...Submission..." } ],
  "myStatus": {
    "isRosterMember": true,
    "mySubmissionId": "…" ,
    "myBallotSubmissionId": null,
    "canSubmit": true,
    "canVote": false
  },
  "result": null
}
```
Visibility of `submissions`:
- `CREATED`: empty.
- `OPEN_FOR_SUBMISSIONS`: only the caller's own submission.
- `VOTING`: all `ACTIVE` submissions, excluding ones blocked in either direction with the caller (the caller's own is always included).
- `COMPLETED` or `ARCHIVED`: all `ACTIVE` submissions, with the same block rule.
- `CANCELLED`: empty.

`result` is `null` until `COMPLETED`. Then it is:
```json
{
  "outcome": "WINNER",
  "coWinnerSubmissionIds": ["…"],
  "tallies": [{"submissionId":"…","votes":3}],
  "ballotCount": 4,
  "eligibleVoterCount": 4
}
```
Each `Submission`:
```json
{
  "id":"…","challengeId":"…","authorUserId":"…","authorDisplayName":"Ada",
  "status":"ACTIVE","thumbnailMediaId":"…","fullMediaId":"…",
  "width":1600,"height":1067,"isMine":true
}
```
`authorUserId` is `null` for submissions whose author deleted their account.

### GET `/v1/rooms/{roomId}/challenges?cursor=…&limit=20` (member) → `200 {"items":[Challenge],"nextCursor":"…"|null}`
Newest first. Cursor is opaque.

### GET `/v1/me/history?cursor=…&limit=20` (auth) → `200 {"items":[HistoryEntry],"nextCursor":…}`
`HistoryEntry`: `{"challenge":Challenge,"roomName":"…","mySubmissionId":"…"|null,"result":ChallengeResult|null}`.
Covers every room the caller belongs to or belonged to while the challenge was active. Rooms the caller has left are excluded.

## 7. Submissions

### PUT `/v1/challenges/{challengeId}/submission` (roster member) → `201` (new) or `200` (replacement)
- Body: `multipart/form-data`, with fields `full` (JPEG file, 1600 px long edge max, up to 2 MB) and `thumbnail` (JPEG file, 480 px long edge max, up to 300 KB).
- The server **re-encodes both files** (see architecture §9). The client-provided metadata is ignored. Any EXIF data is removed. The server verifies the files are JPEG, PNG or WebP by magic bytes.
- Requires `OPEN_FOR_SUBMISSIONS`, roster membership, and `now < submissionDeadline` (checked under the challenge row lock after the body is read). Otherwise `410 DEADLINE_PASSED` or `409 INVALID_TRANSITION`.
- Replacing an existing submission deletes the old media after the new row commits.
- Returns the `Submission` (with `isMine: true`).

### DELETE `/v1/challenges/{challengeId}/submission` (roster member) → `204`
Withdraws the caller's submission. Allowed only in `OPEN_FOR_SUBMISSIONS`. Otherwise `409 INVALID_TRANSITION`.

### DELETE `/v1/submissions/{submissionId}` (author) → `204`
Allowed in `COMPLETED` or `ARCHIVED`. Media is purged. The submission row becomes `REMOVED`, and the result is kept with the tally unchanged. Otherwise `409 INVALID_TRANSITION`.

### GET `/v1/media/{mediaId}` (auth) → `200 image/jpeg` bytes, or `404 NOT_FOUND`
Authorization follows the policy in architecture §6. Response headers: `Cache-Control: private, max-age=300`, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline`. `ETag` is the stored SHA-256. A stale URL returns `404` as soon as access is revoked.

## 8. Ballots

### POST `/v1/challenges/{challengeId}/ballot` (roster member) → `201` (new) or `200` (same submission already recorded)
Request: `{"submissionId":"…"}`.
Server checks, in order, under the challenge row lock and against server time:
1. Caller is on the roster and still a member (`403 FORBIDDEN` otherwise).
2. State is `VOTING` (`409 INVALID_TRANSITION`).
3. `now < votingDeadline` (`410 DEADLINE_PASSED`).
4. Target is an `ACTIVE` submission in this challenge, not authored by the caller, and not blocked in either direction (`422 INVALID_BALLOT`).
5. The caller has no existing ballot. An existing ballot for the **same** submission returns `200` (idempotent retry). For a **different** submission it returns `409 ALREADY_VOTED`.

Ballots are immutable. There is no update or delete route.

Response `201`: `{"challengeId":"…","submissionId":"…","castAtEpochMillis":…}`.

## 9. Safety

### POST `/v1/reports` (auth) → `201` (or `200` if this reporter already has an open report for the target)
Request: `{"targetType":"SUBMISSION"|"USER","targetId":"…","reason":"INAPPROPRIATE"|"HARASSMENT"|"PRIVACY"|"SPAM"|"NOT_A_PHOTO_OF_PROMPT"|"OTHER","details":"…" optional, max 280}`.
- Caller must share a room with the target. Otherwise `404 NOT_FOUND`.
- Reporting your own content returns `422 VALIDATION`.
- When 3 distinct reporters report the same submission, it becomes `HIDDEN` automatically and is excluded from ballots and galleries until a moderator reviews it.
- Reports are never shown to the reported user.

### GET `/v1/blocks` (auth) → `200 {"blocked":[{"userId","displayName"}]}`
### PUT `/v1/blocks/{userId}` (auth) → `204`
Requires a shared room, otherwise `404`. Effects:
- Each party's photos are hidden from the other.
- Neither party may cast a ballot for the other's submission.
- Neither may read the other's photos through `/v1/media`.
Blocks do not remove anyone from a roster and do not change tallies.

### DELETE `/v1/blocks/{userId}` (auth) → `204`

## 10. Moderation (no HTTP surface)

Moderation is performed only by the local command `npm run mod -- <subcommand>` run by the operator with database access. Subcommands: `list-reports`, `show-report <id>`, `hide-submission <id> --reason "<text>"`, `restore-submission <id>`, `dismiss-report <id>`, `suspend-user <id> --reason "<text>"`, `restore-user <id>`, `purge-room <id>`. Each action writes a row to `audit_log` with the operator name. Suspended users receive `401 UNAUTHENTICATED` on every request.

## 11. Contract tests

Every endpoint above has at least one backend test. The Android data layer has a `mockwebserver` test for each request and response shape. The golden result vectors in `contracts/vectors/results.v1.json` are shared by the Kotlin and Node tallies.
