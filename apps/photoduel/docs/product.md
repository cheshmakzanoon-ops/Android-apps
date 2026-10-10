# PhotoDuel — product charter (PD-PM)

**Status:** MVP scope signed for the development build, 2026-10-10. Not a public release.

## Promise

Run private photo challenges with friends. Create a room, invite friends with a link, everyone submits one photo to the same prompt, everyone votes once for a photo that is not their own, and the server reveals the result.

## Target user and positioning

- **Audience:** small private groups of friends (3–8 people) who already know each other.
- **Not a social network.** No public profiles, no public feed, no discovery, no chat, no location sharing.
- **Age positioning (decision PD-PM-02):** the MVP is positioned for users aged 13 and over, is not directed to children, and collects no real names or contact details. Before any public launch, the PM must complete a content-rating questionnaire and a Families-policy determination. Until then, the store listing says "for ages 13+" only as a draft.

## MVP scope (what this build contains)

1. Guest identity with a server-issued session token and a one-time recovery code.
2. Private rooms (2–8 members, owner plus invited friends).
3. Invitation links with expiry, use limits, revocation and a server-side audit trail. Transport is a custom scheme (`photoduel://invite/{token}`) plus copyable text. App Links are **not** claimed.
4. Challenge lifecycle: create, start (needs at least 3 members), submission window, voting window, results, cancellation, rematch.
5. One photo per member per challenge, replaceable until the submission deadline. The server re-encodes every photo and removes all metadata.
6. One immutable ballot per member, never for their own photo. Ties are co-winners. Results come from the server.
7. Reporting, blocking, account deletion, room deletion, and a moderation CLI with an audit log.
8. A curated catalogue of 30 original prompts. Users cannot write public prompts in this release.

## Explicit non-goals for this release

Public discovery feeds, chat, direct messages, unrestricted user-written prompts, location features, advertising, in-app purchases, cloud AI or generative image APIs, Android App Links, push notifications sent by the server, and web accounts.

## Acceptance criteria (from the directive, with current status)

| # | Criterion | Status in this build |
|---|---|---|
| A1 | Only members see room media | Implemented in backend; verified by backend tests once they run (see release-checklist) |
| A2 | Votes reconcile under retries and simultaneous voting | Implemented with a primary key and a row lock; verified by a concurrency test once it runs |
| A3 | Reports, blocks and deletion function | Implemented; verification pending |
| A4 | Android app builds and runs on a device or emulator | **Not verified.** Build runs only on GitHub Actions; no device used |
| A5 | Two or more real accounts complete a challenge end to end | Backend multi-account test (three identities) is written; Android journey not verified |

## Key metric (from the master outline)

Weekly challenges with two or more participating members. Telemetry is **not** implemented in this build; the events listed in the outline (`room_created`, `invite_opened`, `submitted`, `vote_cast`, `completed_challenge`) are intended only and require PM approval and a privacy review before any collection.

## Cost drivers and budget

See architecture.md section 10. No cloud provider is provisioned. No budget alert exists. Spending thresholds are a PM decision that is still open.
