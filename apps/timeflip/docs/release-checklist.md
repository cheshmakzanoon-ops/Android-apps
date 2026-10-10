# TimeFlip release checklist

Gates G0 to G7 come from `project-outline-android-apps.md` §4. The "Evidence D1–D5 must supply" and "Final
manager action" columns are copied from that section. Owner of this file: TF-PM. Status date: 2026-10-10.

**Status rule.** Only G0 (product scope) and G1 (build scaffold) are "in progress". Everything else is "not
started". Nothing here is signed. A gate closes only when the PM records the decision and the evidence links.

## Gate status

| Gate | Stage | Evidence D1–D5 must supply | Final manager action | Status | Notes |
|---|---|---|---|---|---|
| G0 | Product discovery | Audience, specific need, clickable sample, differentiated feature feasibility spike, MVP/non-goals, data/rights risks | PM signs v1 scope and budget | in progress | Scope is written in `README.md` and outline §5.2. No PM sign-off, audience validation or clickable sample is recorded. Rights risks are not reviewed. |
| G1 | Buildable framework | Root Gradle wrapper, compiling app module, D2 contracts with fakes, D3 schema design, D4 technical spike, D5 green CI | PM accepts modules and independent file ownership | in progress | Gradle wrapper, version catalog, app module, domain layer, `app/` composition root, `platform/` helpers and a CI workflow exist in the working tree. `data/` and most of `engine/` exist, and some seats have written tests. CI has not run. The `ui/` root (`TimeFlipApp`, `TimeFlipTheme`) is missing, and `UserPreferences.clearAll()` is missing, so the app does not build yet. No PM acceptance is recorded. |
| G2 | Vertical slice | Real imported/user input → D1 screen → D2 logic → D3 persistence → D4 actual processing → D5 tested Android output | PM watches real device demo and rejects stub flows | not started | |
| G3 | MVP feature complete | Traceable acceptance matrix; all expected flows and recovery; security/privacy review; assets and licenses; QA automation | PM freezes scope and starts stabilization | not started | |
| G4 | Release candidate | Exact tested SHA/version, lint/unit/UI/device/benchmark reports, all blocker issues closed, screenshots match binary | PM formally approves RC or records HOLD | not started | |
| G5 | Play internal/closed test | Verified package/signing/target API/data-safety declarations, testers' installation/upgrade feedback, test-track URL | PM directs required fixes or authorizes launch candidate | not started | |
| G6 | Staged production | Approved signed AAB, store listing, rollout percentages and stop conditions, support contact, crash/error monitoring | PM + privileged operator publish through Play | not started | |
| G7 | Operate | Review sentiment, active retention, core funnel, crash/ANR, cost per active user, incident and support log | PM prioritizes fixes and measures product-market signal | not started | |

## G0 and G1 evidence still missing

- G0: a PM-signed v1 scope with budget; a recorded audience and need; a clickable sample; the feasibility spike
  result for the wipe and dissolve reveal on real photos; rights review of any bundled assets (none are
  bundled today).
- G1: a green `validate` run on GitHub Actions for a specific SHA; a compiling app module at that SHA, which
  requires `data/`, `ui/` and the remaining `engine/` code; PM acceptance of module ownership.

## TimeFlip acceptance criteria (outline §5.2)

Each row is "not verified". The journey test and the MP4 checks have been written but have not run.

| Criterion | Current evidence | Status |
|---|---|---|
| 1. First-time user gets a playable, correctly oriented MP4 without signup | Journey test written (`TimeFlipJourneyTest`). MP4 structure and orientation check written (`verify_mp4.py`, rotation and dimensions). Neither has run on CI. No player test. | not verified |
| 2. Auto-align has a usable manual fallback | Journey waits for the editor entry point, which must appear for both accepted and manual estimates. Manual adjustment UI is not built yet. | not verified |
| 3. Exports play in popular media players | Verifier checks H.264, yuv420p, MP4 container, frame count and duration. No player has been tested. | not verified |

## Release controls that stay outside this repository

Signing, Play Console, data-safety declarations, store listing, privacy policy and the support contact are PM and
privileged-operator tasks (outline §3.6 and §4.1 steps 5, 7 and 8). They are not started. The CI workflow builds
an unsigned release bundle and reads no secrets, by design. No keystore or upload key is in the repository.
