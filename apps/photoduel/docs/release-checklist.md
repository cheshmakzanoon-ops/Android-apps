# PhotoDuel release checklist and gate status (PD-PM)

**Build identity:** `com.agency.photoduel`, versionName `0.1.0-mvp`, versionCode `1`.
**Evidence rule:** a gate is marked **Passed** only when a command or CI run shows it. Everything else is **Open** or **Blocked** with the reason.

| Gate | Requirement | Status | Evidence / reason |
|---|---|---|---|
| G0 | Product scope, audience, non-goals signed | Passed (development) | [product.md](product.md); the PM signature is this document, and it is not a legal review |
| G1 | Buildable root Gradle, contracts with fakes, CI | In progress | Core JVM build configured (see logs in the release notes); Android module awaits CI |
| G2 | Vertical slice on a real device or emulator | **Open** | No device or emulator run exists |
| G3 | MVP feature complete with acceptance matrix | **Open** | Depends on the test runs listed below |
| G4 | Release candidate with exact tested SHA | **Open** | Depends on G3 |
| G5 | Play internal testing | **Blocked** | No Play Console access, no upload key, no signed bundle |
| G6 | Staged production rollout | **Blocked** | Needs a live HTTPS backend, a moderation staff owner, a deletion web page and policy review |
| G7 | Operate | Not started | — |

## Required blockers (cannot be resolved inside this build)

1. **No live backend.** No cloud project, database, bucket, TLS endpoint or budget exists. The backend runs locally and in CI only.
2. **Web account-deletion page.** Google Play requires a web link where users can request account and data deletion when an app allows in-app sign-up. No domain exists. Source: [Google Play account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111). The page must be hosted on a domain the agency controls.
3. **Play Console access and signing.** The release bundle built by CI is unsigned. The upload key lives outside the repository and is not available.
4. **Moderation owner.** A named, reachable person must review reports daily. The moderation procedure in `store/moderation-procedure.md` names the role and leaves the person blank.
5. **Verified App Links.** Not claimed. The invitation scheme is custom only.
6. **Physical devices.** None were used. The QA matrix states which categories are not covered.
7. **Privacy and legal review.** The drafts in `store/` are not legal advice and need counsel review before publication.
8. **Figma fidelity.** Design work must be compared with real screenshots, which require a running app. See design.md.

## Verification commands (the gate evidence once they are run)

- Core JVM: `./gradlew -Pphotoduel.skipAndroid=true :apps:photoduel-core:test --console=plain`
- Backend: `npm ci && npm run build && npm test && npm run test:e2e` in `apps/photoduel/backend` with `TEST_DATABASE_URL`
- Android (CI): `./gradlew :apps:photoduel:testDebugUnitTest :apps:photoduel:lintDebug :apps:photoduel:assembleDebug :apps:photoduel:bundleRelease`
- Instrumented (CI, non-blocking until green): `./gradlew :apps:photoduel:connectedDebugAndroidTest`
