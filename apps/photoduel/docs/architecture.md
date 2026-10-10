# PhotoDuel architecture (frozen contract v1)

**Status:** Frozen for G1 integration on 2026-10-10. Changes require a PD-PM decision entry in `decisions.md` and a revision bump here.
**Owner:** PD-PM (architecture), PD-D2 (domain rules), PD-D3 (backend and data), PD-D4 (engine), PD-D1 (UI), PD-D5 (platform, build, QA).
**Companion:** [api-contract.md](api-contract.md) defines every HTTP endpoint, error code and JSON shape.

## 1. Product rules in one paragraph

A signed-in or guest user creates a private **room** (2–8 members). The owner picks a curated prompt, a submission window and a voting window, then **starts** the challenge once at least **3 members** are present. Each roster member submits **one photo** (replaceable until the submission deadline). After the deadline, every roster member casts **one immutable ballot** for **one photo that is not their own**. When voting closes, the server tallies the ballots and publishes a result. Only room members can see the photos, and only after voting opens.

## 2. Decisions

| # | Decision | Reason |
|---|---|---|
| D-01 | **Backend: a self-hosted API in Node.js 22 + TypeScript (Fastify) on PostgreSQL 16**, with an object-store adapter for photos. | Every rule that matters (one ballot per voter, deadline enforcement, deterministic tally, media authorization) is a transactional database rule. PostgreSQL row locks and unique constraints enforce those rules under concurrent and retried requests. A plain SQL and TypeScript service is runnable and testable here against a real PostgreSQL 16 server. |
| D-02 | Firebase was **rejected** for MVP. | Its security rules cannot express "exactly one ballot per voter, decided under a lock against server time" without Cloud Functions. The emulator suite needs Google-hosted emulator downloads that this environment cannot reach. A production Firebase project requires credentials that this agency does not hold. |
| D-03 | Supabase was **rejected** for MVP, for now. | Its local stack needs Docker image pulls and the hosted project needs credentials. Its row-level security is a strong fit and remains the migration option if the agency later chooses a managed platform. |
| D-04 | **Identity: anonymous guest accounts with a server-issued session token and a one-time recovery code.** No email, phone or real name. | Friction is lowest for a friend group. The limitation is stated in the app: a lost device without a recovery code means losing that identity. |
| D-05 | **Photos are never publicly addressable.** Every media request is authorized per request by the API. No public bucket and no permanent URL. | Required by the private-room promise. Revocation (leaving a room, blocking, deletion) takes effect on the next request. Signed URLs were rejected because they cannot be revoked before expiry. |
| D-06 | **Server re-encodes every upload** (JPEG, long edge max 1600 px, plus a 480 px thumbnail, all metadata removed). | Strips GPS and other EXIF data even if a client is buggy or hostile. It also bounds bandwidth and storage cost. |
| D-07 | **Voting is a single choice per voter**, immutable, self-votes forbidden. Ties are **co-winners** (no hidden tie-break). | Simple to explain, deterministic and fair. Co-winners avoid arbitrary tie-breaks. |
| D-08 | **Deadlines are server-authoritative.** Every state transition is evaluated under a row lock against the database clock, lazily on each request and by a 30-second worker. | A device clock cannot extend or reopen a window. Clients display countdowns from `serverTime` offsets only. |
| D-09 | **Android client: Kotlin, Jetpack Compose, Navigation Compose, OkHttp, kotlinx.serialization, DataStore, Coil 3.** | Consistent with the existing monorepo toolchain (TimeFlip catalog), with no Room needed because the server is the source of truth. |
| D-10 | **Module split: pure-JVM core plus Android app module.** | The domain, remote-data and pure engine code is compiled and unit-tested on a plain JVM with `-Pphotoduel.skipAndroid=true`. This works in environments that cannot reach Google Maven. The Android module is built on GitHub Actions. |
| D-11 | **Invitations use a custom scheme `photoduel://invite/{token}` in this build.** Verified Android App Links are **not** claimed. | No verified domain and no `assetlinks.json` exist yet. The fallback is a copyable text invitation that works without the app being registered as a link handler. |
| D-12 | **Moderation is an authorized server-side CLI** (`npm run mod`) with an audit log. No admin credential exists in the Android client, the repository or the API. | Meets the "no secret administrator credentials in the client" rule. An HTTP admin API can be added later behind a separate secret store. |

## 3. Build and verification environment (what is actually possible)

Verified on 2026-10-10 from the development container:

| Capability | Status | Evidence |
|---|---|---|
| JDK 21, Gradle 8.14.3, Node 22.22, npm registry | Available | Local toolchain probe |
| PostgreSQL 16 server binaries | Available | `/usr/lib/postgresql/16/bin` |
| Gradle plugin portal, Maven Central (rate-limited, HTTP 429 at times), `services.gradle.org` | Reachable | HTTP probes |
| `dl.google.com` (Android SDK, Google Maven artifacts, AGP, AndroidX, Compose) | **Blocked** by egress policy (HTTP 403 CONNECT) | Probe |
| Android SDK platforms and build tools on this machine | **Not present** | Filesystem search |
| Figma editable file | **Created**: `https://www.figma.com/design/u4JSaycaKPivgX6Lr8rgr3` | `create_new_file` result |
| GitHub Actions on `cheshmakzanoon-ops/Android-apps` | Enabled (other teams' workflows exist on their branches) | Actions API |

**Consequence:** Android compilation, lint, unit tests on Android, APK and AAB builds, and instrumented tests are verified by **GitHub Actions on hosted runners**, which have Google Maven and an Android SDK. Locally we verify the pure-JVM core and the backend against a real PostgreSQL server. Nothing is claimed as verified until a CI run or a local run shows it.

## 4. Module layout

```
apps/photoduel/
├── build.gradle.kts                 # Android application module (AGP)   [PD-D5 + PM]
├── core/build.gradle.kts            # Pure-JVM module: compiles domain, data/remote, engine/pure
│                                    #   from the app source tree (no duplicated sources)
├── src/main/kotlin/com/agency/photoduel/
│   ├── app/        composition root, MainActivity, Application      [PD-D5]
│   ├── ui/         Compose screens, ViewModels, navigation, theme   [PD-D1]
│   ├── domain/     pure rules, models, ports, fakes                  [PD-D2]   (JVM-safe)
│   ├── data/       remote API client, DTOs, repository adapter       [PD-D3]   (JVM-safe: data/remote)
│   │   └── remote/ OkHttp + kotlinx.serialization                    (JVM-safe)
│   ├── engine/     pure image math (engine/pure) + Android bitmap pipeline (engine/android) [PD-D4]
│   └── platform/   Photo Picker, camera FileProvider, share intent, deep links, Keystore token cipher [PD-D5]
├── src/main/res/                    # UI resources                      [PD-D1]
├── src/main/AndroidManifest.xml     #                                  [PD-D5]
├── src/test/kotlin/...              # JVM unit tests (domain, data/remote, engine/pure JVM-safe)
├── src/androidTest/kotlin/...
│   ├── ui/         Compose UI tests                                  [PD-D1]
│   └── journey/    multi-account end-to-end journeys                 [PD-D5]
├── assets/source/prompts/prompts.v1.json   # curated catalogue, shared with backend   [PD-D4]
├── contracts/vectors/*.json         # golden vectors shared by Kotlin and Node tests  [PD-D2 + PD-D3]
├── backend/                         # Node.js 22 + TypeScript API, migrations, moderation CLI  [PD-D3]
├── benchmarks/                      # engine benchmarks                 [PD-D4]
├── docs/                            # architecture, api, design, qa, privacy and policy docs
└── store/                           # store listing drafts              [PD-PM]
```

Dependency direction: `ui → domain.port ← data (implementation)`, `engine → domain.model`, `platform → ui/app`. `domain` imports nothing from `android.*`, `okhttp`, `coil` or any file or network API. The JVM core build fails if it does.

## 5. Backend components

| Component | Choice | Notes |
|---|---|---|
| HTTP | Fastify 5 | JSON only, request-body size limits, strict schema validation |
| Database | PostgreSQL 16 | Row locks (`SELECT … FOR UPDATE`) for challenge transitions, unique constraints for ballots and memberships |
| Migrations | Plain SQL files in `backend/migrations/`, applied in order by `npm run migrate` | Forward-only, checksum recorded |
| Image normalization | `sharp` (libvips) | Honors EXIF orientation, strips metadata, caps input pixels at 40 MP |
| Object store | `ObjectStore` interface; implementation `LocalDiskStore` | Production target: S3-compatible private bucket. **Not provisioned here.** |
| Sessions | Random 32-byte bearer token; only SHA-256 hash stored | Expires 90 days after last use |
| Recovery codes | 80-bit random, shown once, stored with scrypt | Rotated on each issue |
| Rate limits | Fixed-window counters in `rate_events` table | Survives multiple instances; purged after 48 h |
| Worker | In-process interval every 30 s | Advances challenges, purges expired data. Safe to run on several instances because each action is idempotent and row-locked |
| Moderation | `npm run mod -- …` | Writes `audit_log`; no network admin surface |
| Logging | Structured JSON; request bodies and tokens never logged | Photo bytes never logged |

## 6. Authorization policy (enforced by the API, never by the client)

| Operation | Allowed when |
|---|---|
| Read a room, its roster or its challenges | Caller is an active member of that room |
| Create a room | Authenticated, under the daily room quota |
| Create an invitation | Active member; at most 5 active invitations per room |
| Preview or join by token | Authenticated; token exists, not revoked, not expired, uses remain, room not deleted, room not full, caller not banned/suspended. Join is idempotent for existing members |
| Create, start or cancel a challenge | Caller is room owner; room has no other active challenge; start requires at least 3 members |
| Submit or replace a photo | Caller is on the roster; state is `OPEN_FOR_SUBMISSIONS`; server time before `submission_deadline`; photo valid |
| Delete own submission | Own submission; state is `OPEN_FOR_SUBMISSIONS`, or the challenge is `COMPLETED` or `ARCHIVED` (media purged, tally kept) |
| Cast a ballot | Caller is on the roster and still a member; state is `VOTING`; server time before `voting_deadline`; target is an active submission not authored by caller and not blocked in either direction; caller has no existing ballot (same target is idempotent, different target is 409) |
| View a photo (media) | Owner of the photo, OR active member of the room AND (challenge is `VOTING`, `COMPLETED` or `ARCHIVED`) AND submission is `ACTIVE` AND no block in either direction AND media not purged |
| Leave a room, or be removed | Not while a challenge is `OPEN_FOR_SUBMISSIONS` or `VOTING` (fairness: roster is frozen) |
| Report | Authenticated; one open report per reporter and target; daily quota |
| Block | Target shares at least one room with caller. Blocking hides the other party's photos and blocks mutual ballots |
| Delete account | Authenticated; body must confirm `"DELETE"` |
| Moderation actions | Only the local CLI with database access. Never exposed to the client |

Every rule above has a backend test. Malicious requests are part of the suite (cross-room reads, double votes, late votes, forged author fields, stale media URLs, unauthenticated media).

## 7. Challenge lifecycle (server-enforced state machine)

```
CREATED ──start (owner, ≥3 members)──▶ OPEN_FOR_SUBMISSIONS ──submission deadline──▶ VOTING ──voting deadline──▶ COMPLETED ──30 days──▶ ARCHIVED
   │                                        │                                          │
   └──cancel (owner)──▶ CANCELLED ◀──cancel (owner)                              (no ballots / <2 photos: COMPLETED directly with an outcome)
```

Allowed transitions only. Any other transition returns `409 invalid_transition`.

- `OPEN_FOR_SUBMISSIONS → VOTING` happens when `submission_deadline` has passed. If fewer than 2 active submissions exist, the challenge goes straight to `COMPLETED` with outcome `INSUFFICIENT_SUBMISSIONS`.
- `VOTING → COMPLETED` happens when `voting_deadline` has passed. The result is computed once, in the same transaction (see §8). The `challenge_results` primary key makes the computation idempotent.
- `COMPLETED → ARCHIVED` happens 30 days after completion. Photos are purged; the result row is kept.
- `CANCELLED` is terminal. Photos of a cancelled challenge are purged within 24 hours.
- Starting a challenge fixes the roster and both deadlines: `submission_deadline = started_at + submission_hours`, and `voting_deadline = submission_deadline + voting_hours`.

## 8. Voting and results (deterministic, identical on server and client)

Inputs at voting close:

1. `P` = `ACTIVE` submissions whose author is on the frozen roster.
2. `B` = counted ballots. A ballot counts only when all of the following hold: the voter is on the roster and still a member; the target is in `P`; the voter is not the target's author; the voter and the target's author are not blocked in either direction. Only the **first** ballot per voter counts, because ballots are immutable.
3. `E` = roster members who are still members at close (`eligibleVoterCount`).

Rules:

- If `|P| < 2`: outcome `INSUFFICIENT_SUBMISSIONS`. No winner. Tallies are empty and `ballotCount` is 0.
- Else if `|B| < 2`: outcome `INSUFFICIENT_BALLOTS`. No winner. Tallies are shown for every `p ∈ P` for transparency.
- Else tally `votes(p) = |{b ∈ B : b.target = p}|` for each `p ∈ P`. Let `max = max votes`, `W = {p : votes(p) = max}`.
  - `|W| = 1`: outcome `WINNER`. `coWinnerSubmissionIds` contains exactly that one submission.
  - `|W| > 1`: outcome `TIE`, co-winners = `W`, sorted by submission id.
- Submissions are considered at voting close, not earlier. A photo that is removed or hidden before close does not appear in `P`, and ballots for it are void.
- Result output includes per-submission counts, `ballotCount`, `eligibleVoterCount`, outcome and co-winner list. Voter identities are never published (secret ballot).

Golden vectors for these rules live in `contracts/vectors/results.v1.json` (ten cases, including tie, void self-vote, void blocked pair, removed-target ballot, first-ballot-only, and insufficient outcomes). Both the Kotlin `ResultCalculator` and the Node `tally` function must pass every vector.

## 9. Media pipeline and privacy

Client (PD-D4, `engine/android`): decode with `inSampleSize`, honor EXIF orientation, resize to a long edge of 1600 px, JPEG quality 85 (raising or lowering as needed to stay under 1.5 MB), **re-encode without EXIF**, produce a 480 px thumbnail. Original picker data is never read beyond the chosen image.

Server (PD-D3): accept only JPEG, PNG or WebP, detected by magic bytes and not by filename or `Content-Type`. Reject bodies over 3 MB, images over 40 MP, and images with a short edge under 200 px. Re-encode with `sharp` (orientation applied, metadata removed). Store the full and thumbnail variants separately. The stored file's SHA-256 is recorded. Logs never contain photo bytes, EXIF data or tokens.

Upload is transactional: the server checks the deadline and state under a row lock **after** the body has been read, then writes the media rows and commits. An object written before a failed commit is deleted (orphan sweeper also removes objects older than one hour that have no row).

## 10. Abuse and cost controls (server constants, configurable by environment)

| Control | Default |
|---|---|
| Guest identities per IP hash | 10 per hour |
| Rooms created per user | 5 per day |
| Active invitations per room | 5 |
| Invitation lifetime | 24, 72 or 168 hours (default 72) |
| Invitation uses | Up to room capacity |
| Room capacity | 2–8 members (owner included) |
| Minimum members to start | 3 |
| Photo uploads per user | 30 per day, 60 MB per day |
| Upload body limit | 3 MB |
| Ballots per user | 1 per challenge; 60 request per minute |
| Reports per user | 20 per day; one open report per reporter and target |
| Auto-hide threshold | 3 distinct reporters on one submission hides it pending review |
| Submission window options | 1, 6, 24, 48 or 72 hours |
| Voting window options | 1, 6 or 24 hours |
| Max concurrent challenges per room | 1 |

Cost drivers, in order: (1) image egress, mitigated by thumbnails and `Cache-Control: private` with Coil disk cache; (2) object storage, about 230 KB per photo pair and purged after 30 days; (3) PostgreSQL size, small because rows are small; (4) CPU for `sharp` re-encoding, bounded by the 3 MB and 40 MP limits. No automatic cloud spending cap exists in a self-hosted deployment. The operator must configure provider budget alerts. None are claimed here.

## 11. Retention and deletion

| Data | Rule |
|---|---|
| Photos of a completed challenge | Purged 30 days after completion, then the challenge is `ARCHIVED` |
| Photos of a cancelled challenge | Purged within 24 hours |
| Orphan uploads without a database row | Purged after 1 hour |
| Deleted room | Hidden immediately; photos purged within 24 hours; rows removed after 7 days |
| Deleted account | Session revoked immediately. Photos purged immediately. Display name cleared and replaced by "Deleted player" in historical results. Ballots kept without voter identity (`voter_id` set to NULL) so tallies remain reproducible |
| Sessions | Expired sessions purged daily |
| Rate-limit events | Purged after 48 hours |
| Audit log | Kept 180 days |
| Reports | Kept 180 days after resolution |

## 12. Kotlin contract (frozen signatures, implemented by PD-D2 and PD-D3, consumed by PD-D1, PD-D4 and PD-D5)

All suspend functions return `AppResult<T>`. No function throws for an expected business or network failure.

```kotlin
package com.agency.photoduel.domain.model

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: PhotoDuelError) : AppResult<Nothing>
}

enum class ChallengeState { CREATED, OPEN_FOR_SUBMISSIONS, VOTING, COMPLETED, ARCHIVED, CANCELLED }
enum class ChallengeOutcome { WINNER, TIE, INSUFFICIENT_SUBMISSIONS, INSUFFICIENT_BALLOTS }
enum class MemberRole { OWNER, MEMBER }
enum class SubmissionStatus { ACTIVE, WITHDRAWN, REMOVED, HIDDEN }
enum class MediaVariant { FULL, THUMBNAIL }
enum class ReportReason { INAPPROPRIATE, HARASSMENT, PRIVACY, SPAM, NOT_A_PHOTO_OF_PROMPT, OTHER }
enum class InvitationTtl(val hours: Int) { ONE_DAY(24), THREE_DAYS(72), ONE_WEEK(168) }

data class UserProfile(val id: String, val displayName: String, val hasRecoveryCode: Boolean)
data class Session(val user: UserProfile, val token: String, val expiresAtEpochMillis: Long)
data class RecoveryCode(val code: String)                         // shown once

data class Prompt(val id: String, val title: String, val brief: String)
data class PromptCatalog(val version: String, val prompts: List<Prompt>)

data class RoomSummary(val id: String, val name: String, val memberCount: Int, val capacity: Int, val activeChallengeState: ChallengeState?)
data class RoomMember(val userId: String, val displayName: String, val role: MemberRole)
data class RoomDetail(val id: String, val name: String, val capacity: Int, val members: List<RoomMember>, val viewerRole: MemberRole, val viewerUserId: String)
data class CreatedInvitation(val id: String, val token: String, val expiresAtEpochMillis: Long)  // token shown once
data class InvitationPreview(val roomName: String, val ownerDisplayName: String, val memberCount: Int, val capacity: Int, val expiresAtEpochMillis: Long)

data class ChallengeSpec(val promptId: String, val submissionHours: Int, val votingHours: Int)
data class Challenge(
    val id: String, val roomId: String, val roundNumber: Int, val prompt: Prompt,
    val state: ChallengeState, val outcome: ChallengeOutcome?,
    val submissionDeadlineEpochMillis: Long?, val votingDeadlineEpochMillis: Long?,
    val serverTimeEpochMillis: Long
)
data class Submission(
    val id: String, val challengeId: String, val authorUserId: String, val authorDisplayName: String,
    val status: SubmissionStatus, val thumbnailMediaId: String, val fullMediaId: String,
    val width: Int, val height: Int, val isMine: Boolean
)
data class Tally(val submissionId: String, val votes: Int)
data class ChallengeResult(
    val outcome: ChallengeOutcome, val coWinnerSubmissionIds: List<String>,
    val tallies: List<Tally>, val ballotCount: Int, val eligibleVoterCount: Int
)
data class MyStatus(
    val isRosterMember: Boolean, val mySubmissionId: String?, val myBallotSubmissionId: String?,
    val canSubmit: Boolean, val canVote: Boolean
)
data class ChallengeView(
    val challenge: Challenge, val roster: List<RoomMember>, val submissions: List<Submission>,
    val myStatus: MyStatus, val result: ChallengeResult?
)
data class Page<T>(val items: List<T>, val nextCursor: String?)
data class HistoryEntry(val challenge: Challenge, val roomName: String, val mySubmissionId: String?, val result: ChallengeResult?)
data class BlockedUser(val userId: String, val displayName: String)

sealed interface ReportTarget {
    data class Submission(val submissionId: String) : ReportTarget
    data class User(val userId: String) : ReportTarget
}

data class PreparedPhoto(val fullJpeg: ByteArray, val thumbnailJpeg: ByteArray, val width: Int, val height: Int)

// One entry per error code in api-contract.md §3, plus client-only codes (NETWORK, TIMEOUT, UNKNOWN).
enum class ErrorCode {
    VALIDATION, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, INVITE_UNAVAILABLE,
    CONFLICT, INVALID_TRANSITION, ALREADY_VOTED, ROSTER_FROZEN, ROOM_FULL,
    DEADLINE_PASSED, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA, INVALID_BALLOT,
    RATE_LIMITED, SERVER,
    NETWORK, TIMEOUT, UNKNOWN
}
data class PhotoDuelError(val code: ErrorCode, val message: String, val retryable: Boolean, val retryAfterSeconds: Int? = null)

package com.agency.photoduel.domain.port

interface PhotoDuelService {
    // Identity
    suspend fun createGuest(displayName: String): AppResult<Session>
    suspend fun restoreWithRecoveryCode(code: String): AppResult<Session>
    suspend fun issueRecoveryCode(): AppResult<RecoveryCode>
    suspend fun currentUser(): AppResult<UserProfile>
    suspend fun renameSelf(displayName: String): AppResult<UserProfile>
    suspend fun logout(): AppResult<Unit>
    suspend fun deleteAccount(): AppResult<Unit>

    // Catalogue
    suspend fun prompts(): AppResult<PromptCatalog>

    // Rooms and invitations
    suspend fun myRooms(): AppResult<List<RoomSummary>>
    suspend fun createRoom(name: String): AppResult<RoomDetail>
    suspend fun room(roomId: String): AppResult<RoomDetail>
    suspend fun leaveRoom(roomId: String): AppResult<Unit>
    suspend fun removeMember(roomId: String, userId: String): AppResult<Unit>
    suspend fun deleteRoom(roomId: String): AppResult<Unit>
    suspend fun createInvitation(roomId: String, ttl: InvitationTtl): AppResult<CreatedInvitation>
    suspend fun revokeInvitation(roomId: String, invitationId: String): AppResult<Unit>
    suspend fun previewInvitation(token: String): AppResult<InvitationPreview>
    suspend fun joinWithInvitation(token: String): AppResult<RoomDetail>

    // Challenges
    suspend fun createChallenge(roomId: String, spec: ChallengeSpec): AppResult<Challenge>
    suspend fun startChallenge(challengeId: String): AppResult<Challenge>
    suspend fun cancelChallenge(challengeId: String): AppResult<Challenge>
    suspend fun challenge(challengeId: String): AppResult<ChallengeView>
    suspend fun roomHistory(roomId: String, cursor: String?): AppResult<Page<Challenge>>
    suspend fun myHistory(cursor: String?): AppResult<Page<HistoryEntry>>

    // Submissions, ballots, media
    suspend fun submitPhoto(challengeId: String, photo: PreparedPhoto, onProgress: (Float) -> Unit): AppResult<Submission>
    suspend fun withdrawSubmission(challengeId: String): AppResult<Unit>
    suspend fun deleteSubmission(submissionId: String): AppResult<Unit>
    suspend fun castBallot(challengeId: String, submissionId: String): AppResult<Unit>
    fun mediaUrl(mediaId: String): String          // authorization header is added by the data layer

    // Safety
    suspend fun report(target: ReportTarget, reason: ReportReason, details: String?): AppResult<Unit>
    suspend fun blockedUsers(): AppResult<List<BlockedUser>>
    suspend fun setBlocked(userId: String, blocked: Boolean): AppResult<Unit>
}

package com.agency.photoduel.domain.port

interface SessionStore {                 // data layer persists the token; platform encrypts it
    suspend fun read(): String?
    suspend fun write(token: String?)
}

interface ServerClock {                  // data layer keeps the offset from the Date response header
    fun nowEpochMillis(): Long
}
```

Notes on the contract:

- `Submission.thumbnailMediaId` and `fullMediaId` are opaque ids. Clients build URLs with `PhotoDuelService.mediaUrl`. Authorization is added in the data layer, so the UI never handles tokens.
- `ChallengeView.submissions` contains only the submissions the viewer may see. During `OPEN_FOR_SUBMISSIONS`, other members' submissions are omitted entirely, and the viewer receives only its own.
- `result` is `null` until the challenge is `COMPLETED`.
- Clients must treat `serverTimeEpochMillis` as the reference for all countdowns and show a "reconnecting" state when the offset cannot be refreshed.

## 13. Test strategy

| Layer | Tooling | Runs where |
|---|---|---|
| Domain rules and state machine | JUnit 4 in the pure-JVM core | Local and CI (`:apps:photoduel:core:test`) |
| Golden vectors | JUnit (Kotlin) and `node --test` (backend) against `contracts/vectors/*.json` | Local and CI |
| Remote data layer | JUnit 4 with `mockwebserver` | Local and CI |
| Engine pure math and JPEG metadata stripping | JUnit 4 with fixture JPEGs | Local and CI |
| Backend unit and security tests | `node --test` with a real PostgreSQL 16 database (`TEST_DATABASE_URL`) | Local and CI with a Postgres service container |
| Backend multi-account end-to-end | Node test with three real sessions talking to a running server | Local and CI |
| Android unit tests, lint, debug APK, release AAB (unsigned) | Gradle on GitHub Actions | CI only (Google Maven required) |
| Compose UI tests | `androidTest` on an emulator | CI, when an emulator job is green. Not claimed until a run passes |
| Multi-account Android journey | `androidTest/journey` against a CI-started backend | CI, when green. Not claimed until a run passes |
| Physical devices, TalkBack, low-RAM, permission denial | Manual | **Not performed in this build.** Listed in `qa-test-plan.md` as blockers |

## 14. Production deployment blockers (recorded, not hidden)

1. No cloud account, database, bucket, domain or TLS certificate has been provisioned for the agency. No production backend URL exists.
2. No Play Console access, upload key or Play App Signing setup exists. The release artifact is an unsigned AAB produced by CI.
3. No verified domain or `assetlinks.json`. App Links are not claimed.
4. No moderation staffing, incident owner, support address or legal review has been performed.
5. Budget alerts for the chosen provider are not configured.

These are release-gate blockers. They are listed again in `release-checklist.md`.
