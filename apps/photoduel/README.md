# PhotoDuel

**Status:** G1 development build in progress on branch `claude/lucid-bardeen-2pnmz2`. Not released. No device run, no live backend and no Play listing exist. The evidence and blockers are listed in [docs/release-checklist.md](docs/release-checklist.md).

**Promise:** run private photo challenges with friends. Create a room, invite friends, submit one photo each, vote once for a photo that is not your own, and see the server-verified result.

**Audience:** small private groups of friends (ages 13 and over; see [docs/decisions.md](docs/decisions.md) PD-PM-02).

**Excluded from this release:** public feeds, discovery, chat, direct messages, user-written public prompts, location features, advertising, App Links, server push notifications.

## What is in the repository

| Path | What it is | Owner |
|---|---|---|
| `src/main/kotlin/com/agency/photoduel/domain/` | Pure game rules, models, ports and an in-memory fake | PD-D2 |
| `src/main/kotlin/com/agency/photoduel/data/remote/` | HTTP client for the backend contract (pure JVM) | PD-D3 |
| `src/main/kotlin/com/agency/photoduel/engine/` | Photo preparation and metadata stripping (pure math and Android pipeline) | PD-D4 |
| `src/main/kotlin/com/agency/photoduel/ui/` | Jetpack Compose screens and presentation state | PD-D1 |
| `src/main/kotlin/com/agency/photoduel/platform/`, `app/` | Photo Picker, invitations, deep links, session cipher, composition root | PD-D5 |
| `backend/` | Node.js 22 and TypeScript API with PostgreSQL migrations, moderation CLI and tests | PD-D3 |
| `core/` | Build for the pure JVM packages (`:apps:photoduel-core`) | PD-PM |
| `contracts/vectors/` | Golden tally vectors shared by Kotlin and Node | PD-D2, PD-D3 |
| `assets/source/prompts/` | Curated prompt catalogue (30 prompts) | PD-D4 |
| `docs/` | Architecture, API contract, design, QA plan, decisions, release checklist | PD-PM and others |
| `store/` | Draft privacy policy, community guidelines, moderation procedure, deletion policy, listing and changelog | PD-PM |

## Build and test

Pure JVM (runs without Google Maven):

```sh
cd /home/user/Android-apps
./gradlew -Pphotoduel.skipAndroid=true :apps:photoduel-core:test --console=plain
```

Backend (needs PostgreSQL 16 and `TEST_DATABASE_URL`; see `backend/README.md`):

```sh
cd apps/photoduel/backend && npm ci && npm run build && npm test && npm run test:e2e
```

Android (runs on GitHub Actions; needs Google Maven and the Android SDK):

```sh
./gradlew :apps:photoduel:testDebugUnitTest :apps:photoduel:lintDebug :apps:photoduel:assembleDebug :apps:photoduel:bundleRelease
```

The release bundle is **unsigned**. No signing material is in the repository.

## Architecture and contracts

- [docs/architecture.md](docs/architecture.md): backend choice, authorization policy, lifecycle, tally rules, privacy, retention and blockers.
- [docs/api-contract.md](docs/api-contract.md): every HTTP endpoint and error code.
- [docs/design.md](docs/design.md): Figma file and design-to-code status.
- [docs/qa-test-plan.md](docs/qa-test-plan.md): what is automated, what is manual and what has not run.
- [docs/security-report.md](docs/security-report.md): the threat model and the tests that verify it, with their actual results.

## Repository

https://github.com/cheshmakzanoon-ops/Android-apps
