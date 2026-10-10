# Project Outline: Android Apps Agency (10 Products / 10 Squads)

**Authoritative file:** `project-outline-android-apps.md`  
**GitHub:** https://github.com/cheshmakzanoon-ops/Android-apps  
**Plan revision:** 1 — October 10, 2026  
**Status:** Planning and organizational scaffold only. No runnable Android builds, staffed teams, published apps or production releases are implied.

> **Agency objective:** Develop 10 independently installable, privacy-conscious, shareworthy native Android products inside one GitHub monorepo using **10 distinct app squads**. Each squad has **five developers working concurrently** and **one project manager who integrates, verifies and finalizes a signed app**. This is a design for 50 developer seats + 10 PM seats, not a statement that 60 people are currently employed or invited.

## 1. Portfolio, product identity and team seats

| Team | App | Gradle project when bootstrapped | Seats | First-release promise |
|---|---|---|---|---|
| SJ | StickerJet | `:apps:stickerjet` | SJ-D1…SJ-D5, SJ-PM | Convert short personal videos into original animated stickers with reliable sharing. |
| TF | TimeFlip | `:apps:timeflip` | TF-D1…TF-D5, TF-PM | Create visually smooth before/after transformation videos from two user photos. |
| PD | PhotoDuel | `:apps:photoduel` | PD-D1…PD-D5, PD-PM | Run private photo challenges with friend invitations and fair voting. |
| RW | RetroWorld | `:apps:retroworld` | RW-D1…RW-D5, RW-PM | Transform photos into distinct original retro-game-inspired visuals and short clips. |
| FO | FluidOS | `:apps:fluidos` | FO-D1…FO-D5, FO-PM | Create beautiful interactive live wallpapers that respect phone battery and lifecycle. |
| SB | SnapBrain | `:apps:snapbrain` | SB-D1…SB-D5, SB-PM | Search text inside consented screenshots using private, on-device OCR. |
| BB | BeatBloom | `:apps:beatbloom` | BB-D1…BB-D5, BB-PM | Turn a hum, whistle or tap into a short editable music arrangement. |
| BF | BillFlash | `:apps:billflash` | BF-D1…BF-D5, BF-PM | Scan a receipt, verify line items and calculate a transparent exact split. |
| PC | PocketCompanion | `:apps:pocketcompanion` | PC-D1…PC-D5, PC-PM | Grow an original tiny creature by completing focus sessions and optional healthy routines. |
| WW | WalkWorld | `:apps:walkworld` | WW-D1…WW-D5, WW-PM | Turn a consented walking route into an original shareable miniature animated world. |

One product equals one distinct Android application ID, version stream, Google Play listing, privacy policy, signing/protected upload workflow, QA plan, release artifact and release decision. No product is allowed to depend on another's backend or customer data. Reuse stable, reviewed code if justified, not personal data.

### 1.1 The five engineering lanes, repeated independently for **each** of ten apps

| Seat | Specific accountable discipline | **Exclusive default** source subtree in `apps/{slug}/` | End-of-sprint handoff |
|---|---|---|---|
| D1 — Android UX/UI developer | Kotlin/Compose app screens, navigation, animations, accessibility, error/loading/empty states, icon/UI visuals | `src/main/kotlin/com/agency/{slug}/ui/**`, UI-owned `src/main/res/**` | Demonstrable screen flow wired to D2 fake contracts; screenshots, accessibility review |
| D2 — feature/domain developer | Core feature rules, pure Kotlin logic, models, typed ports, validators, state machines and contract mocks | `src/main/kotlin/com/agency/{slug}/domain/**` and domain unit tests | Versioned interfaces, user-story rules and deterministic passing tests for D1/D3/D4 |
| D3 — persistence/backend developer | Room/DataStore where suitable, local files, REST/API/auth where needed, authorization, migrations, retention, privacy | `src/main/kotlin/com/agency/{slug}/data/**` and persistence/backend tests | Repository implementations of D2 ports; documented schema, threat model and error behaviors |
| D4 — specialized algorithm/media developer | Differentiating engine: image/video processing, graphics, ML/OCR, DSP, games/animations or geometry | `src/main/kotlin/com/agency/{slug}/engine/**`, `assets/source/**`, engine benchmarks | Reproducible processing demo, output fixtures, capability/fallback contracts and performance results |
| D5 — Android platform/QA developer | Manifest and OS adapters, scoped permissions, services, performance, instrumentation, CI and device lab | `src/main/kotlin/com/agency/{slug}/platform/**`, `src/androidTest/**`, `benchmarks/**`, `docs/qa-test-plan.md` | Physical-device evidence, green end-to-end matrix and identified defects/risks |
| PM — project and release manager | Product vision/scope, ticket sequencing, integration acceptance, cross-lane arbitration, regulatory review, asset approvals, final delivery | `apps/{slug}/README.md`, `TEAM.md`, `docs/product.md`, `docs/decisions.md`, `docs/release-checklist.md`, `store/**` | G0–G7 sign-offs; audited release candidate, Play-ready signed AAB via privileged operator, approved rollout |

**Important:** Every developer must write unit tests for their own code; D5 independently owns automation and release qualification, not all tests. D1 owns visual resources, D4 owns engine assets; any contested file requires an explicit owner assigned by the PM for that PR. D2 domain must not depend on Android Context, databases or codecs. UI uses stable use-case interfaces, never direct database or cloud SDK calls. D3/D4 supply adapters behind contracts; D5 assists composition and OS lifecycle.

Each app's six role IDs are independent. The PM is one of the sixty seats, **not** an extra shared project manager across teams. Real developers should be assigned to the ID positions in `TEAM.md` only after staffing; never invent GitHub handles or mark vacant seats staffed.

## 2. Planned GitHub monorepo structure

The repository contained just a minimal root README before this initialization. The added app README/TEAM documents and portfolio governance files are **actual scaffolding**; the Kotlin/Gradle files below are an **implementation target for G1**, not existing production source.

~~~text
Android-apps/
├── README.md                         # existing portfolio README
├── project-outline-android-apps.md  # this master plan
├── .gitignore
├── .github/
│   ├── PULL_REQUEST_TEMPLATE.md
│   ├── CODEOWNERS                   # add only real, permissioned usernames
│   ├── ISSUE_TEMPLATE/{feature,defect,release}.yml
│   └── workflows/{validate,build,instrumented,release}.yml
├── docs/
│   └── agency/
│       ├── STATUS.md
│       ├── RELEASE_CHECKLIST_TEMPLATE.md
│       ├── architecture-decisions/
│       ├── security-and-privacy/
│       └── retrospectives/
├── gradle/libs.versions.toml         # reviewed dependencies (G1)
├── gradle/wrapper/                   # verified Gradle Wrapper (G1)
├── gradlew                           # (G1)
├── gradlew.bat                       # (G1)
├── gradle.properties                 # (G1)
├── settings.gradle.kts               # includes 10 :apps:{slug} modules (G1)
├── build.gradle.kts                  # minimum root conventions (G1)
├── build-logic/                       # vetted convention plugins if justified
├── platform/                          # optional vetted shared libraries
│   ├── test-support/
│   ├── design-system/
│   └── observability-contract/
├── tools/{ci,benchmark,release}/
└── apps/
    ├── stickerjet/
    ├── timeflip/
    ├── photoduel/
    ├── retroworld/
    ├── fluidos/
    ├── snapbrain/
    ├── beatbloom/
    ├── billflash/
    ├── pocketcompanion/
    └── walkworld/
~~~

### 2.1 Each app's target on-disk contract (same layout × 10)

~~~text
apps/{slug}/
├── README.md                        # PM charter, current status
├── TEAM.md                          # role IDs, real staff when assigned
├── build.gradle.kts                 # Android application module (G1)
├── proguard-rules.pro
├── assets/
│   ├── licenses/                    # artist/library sources and usage rights
│   └── source/                      # original/editable media inputs
├── docs/
│   ├── product.md                   # PM: target user, MVP, non-goals, acceptance
│   ├── architecture.md              # interface and composition decisions
│   ├── contracts.md                 # D2 API design, fakes, data/error contracts
│   ├── privacy-and-threat-model.md  # D3/D5 draft, PM approves
│   ├── qa-test-plan.md              # D5 owns
│   ├── decisions.md                 # PM change and tradeoff log
│   ├── metrics.md                   # value, retention, quality and cost events
│   ├── release-checklist.md         # PM gate status
│   └── releases/{version}/          # SHA-specific evidence packet
├── store/
│   ├── listing.md                   # PM Play content and declarations
│   ├── screenshots/                 # actual build screenshots
│   ├── privacy-policy-link.md
│   └── changelog.md
├── src/
│   ├── main/
│   │   ├── AndroidManifest.xml      # D5 proposes, PM approves sensitive perms
│   │   ├── kotlin/com/agency/{slug}/
│   │   │   ├── app/                 # D5 wires composition root, reviewer approval
│   │   │   ├── ui/                  # D1
│   │   │   ├── domain/              # D2
│   │   │   ├── data/                # D3
│   │   │   ├── engine/              # D4
│   │   │   └── platform/            # D5
│   │   └── res/                     # D1 UI resources; engine res per approved owner
│   ├── test/                        # all authors write tests
│   └── androidTest/                 # D5 critical journeys
└── benchmarks/                      # D4 measurements + D5 independent validation
~~~

**Architecture choice:** one root Gradle multi-project build and ten separate app modules, not ten nested Gradle roots. Each future app should be independently buildable with `./gradlew :apps:{slug}:assembleDebug`. The Gradle wrapper and these modules **do not yet exist**; successful tasks are G1 proof. Keep UI → D2 domain ports, D3/D4 implementation behind typed interfaces, D5 composing OS dependencies. Avoid too many tiny feature modules until demonstrated needs arise. Shared `platform/` is opt-in through an architectural decision (ADR), tests and two real consumers.

**Current official publishing baseline (checked October 10, 2026):** new standard Android apps and updates submitted to Google Play must target Android 16 / API 36 or higher under the August 31, 2026 requirement (exceptions/extensions apply); recheck at release. Use appropriately verified Android Gradle Plugin/Gradle/Kotlin/JDK compatibility, Android Photo Picker/scoped permissions, Play App Signing and a signed App Bundle. Select minimum SDK based on device test coverage and needed APIs. Keystores and credentials never enter Git.

## 3. Software agency work management

### 3.1 Root stewardship without creating additional employees

The **PM Council** means the ten existing PMs; elect one weekly **rotating root steward** and a backup from those ten. This is not an eleventh manager. Only steward-approved root changes modify Gradle wrapper/catalog, root CI, `settings.gradle.kts` or `platform/**`, and an affected squad's D5 must review them. Root changes build all ten app modules after G1. Other app PRs trigger only the affected app tests, except when a shared dependency changed.

The repository is owned by a **personal GitHub account**, so do not presume GitHub organization team slugs work. Until real collaborators are added, represent owners as ID labels and `TEAM.md` role positions, then populate GitHub CODEOWNERS with actual permitted GitHub handles. The repo owner retains administration; PMs request least-privilege access.

### 3.2 Git branching, issues and review contract

**Canonical trunk:** `main`. Bootstrap commits may directly initialize the empty repo; once staffed, enforce branch protection/rulesets and never bypass required reviews for a routine feature.

**Work branch pattern:** `app/{slug}/d1/{issue}-feature` … `app/{slug}/d5/{issue}-test`, with optional `app/{slug}/pm/{version}-integration` only for bounded integration. Examples: `app/timeflip/d4/TF-024-frame-alignment`, `app/billflash/d2/BF-031-tax-rounding`.

**Pull request requirements:** issue number, human need/acceptance, written areas, contract/migration impact, user-facing screenshots or engine output, actual build/test commands and links, test device/API, privacy/license/performance effects, rollback and limitations. Each PR needs one non-author engineering reviewer knowledgeable about changed files. PM acceptance is additionally required for MVP-facing changes; D5/security approval is required for permission, backend access or release metadata changes. Self-review does not count. Small PRs and squash merges are default; never wait until the end to merge five giant branches.

**GitHub issue labels:** `app:stickerjet` … `app:walkworld`; `lane:ui`/`domain`/`data`/`engine`/`platform-qa`/`pm`; `type:feature`/`bug`/`release`/`security`; `priority:P0`…`P3`; `gate:G0`…`G7`; `status:blocked`. Codes such as `SJ-001` are **planning ticket IDs**, not actual GitHub issue numbers—link both once issues are created.

**Lifecycle:** Backlog → Ready → In progress → In review → Integrated → Verified → Done. A ticket is Ready only with owner/paths, testable acceptance, dependencies, failure states, privacy/performance implications and an available interface or mock. Done requires merged code and current test evidence, not merely a commit.

### 3.3 Simultaneous five-developer workflow, not sequential handoffs

**Day 0 — PM+all five:** define product's single indispensable value loop and risks. **Day 1 — D2 leads:** typed domain contract, fake adapters and happy/error state matrix reviewed by D1/D3/D4/D5. **Day 2 — five independent workstreams:** D1 builds UI against fake domain; D2 tests pure rules; D3 implements repositories and auth as needed; D4 proves media/graphics/DSP algorithm; D5 builds Android integration, CI and smoke tests. **Day 3 and after — continuous integration:** D3/D4 replace mocks behind accepted APIs; D5 automatically exercises a real end-to-end flow; PM demos full function, prioritizes defects and makes gate decision. Those are desired checkpoints, **not a rigid 3-day promise for technically complex products**.

Each developer has one main active PR and one fallback task. Publish contracts early, rebase often, and keep work in owned subtrees. If D2 interface changes, D2 updates contract + mocks and notifies every consumer first. D5 starts testing before code freeze. If two lanes must edit one file, PM sets one integrator and records other contributors as reviewers; avoid concurrent file collisions.

**Agency meetings:** 15-minute daily blocker review per squad; twice-weekly merge/demo session; weekly physical-device product demo; weekly PM Council dependency/SDK/policy review; one retrospective per sprint. No cross-team meeting may prevent individual app releases.

### 3.4 Engineering Definition of Done

For each feature: real behavior, failure/cancellation paths, accessibility, permission-denial behavior, author-written unit/contract tests, independent review, no known unhandled privacy/security risk, no unresolved P0/P1, passing affected CI and a reproducible demo on appropriate hardware.

For each app release: verified unique package ID, monotonic versionCode and tagged source SHA, secure upload signing, current Play policy/data safety/content rating, correct privacy policy and support link, licensed creative assets, real store screenshots, cold install + upgrade + uninstall/data-deletion review, instrumented physical-device testing, and owner-approved rollout/monitoring.

### 3.5 Build/test strategy and objective evidence

At G1, PM Council/D5s build root Gradle skeleton using stable documented JDK/AGP/Kotlin versions and version catalog. The acceptance command for TimeFlip, for example, is `./gradlew :apps:timeflip:assembleDebug :apps:timeflip:testDebugUnitTest :apps:timeflip:lintDebug`. A command written in this document is **not proof it has been run**.

After G1, path-filter PR CI to the touched app(s); root/config/shared changes trigger all ten. Require lint, compilation, unit/contract suite, dependency/security checks, permission manifest diff and reproducible artifact hash; instrumented UI tests on device/emulator for critical paths. Camera, mic, GPS, live wallpapers, codec/GPU/thermal work requires physical phones.

Minimum practical test dimensions: supported lowest API and latest current API (including API 36), low-RAM and modern ARM64, orientation and 200% text where applicable, TalkBack, no permissions/partial grants, offline/slow network, process death, app upgrade and large malformed input. Log actual devices/results; never fabricate a device lab report. D5 owns app QA matrix while feature authors test their layers.

**Internal goals, adjustable based on beta evidence:** 0 outstanding P0/P1 security, privacy, data-loss or core-crash bugs; all core activation/export flows covered; crash-free sessions ≥99.5% when there is representative beta data; clear per-app render/battery/memory budget benchmarked on two device classes. These are targets, not promises of actual measured quality.

### 3.6 Privacy, cost and signing boundaries

Media, screenshots, recordings, route geometry and receipt images remain on-device by default. D3 documents any transfer, its legal basis/consent, security and retention. D5 verifies denied/partial permissions and prevents sensitive content in logs. No APK/AAB binaries, private keys, service-account JSON, signing files or real user media in Git. Backend apps (notably PhotoDuel) use independently secured tenants, server-side validation, rate limits, retention budgets and incident ownership. PM approves every SDK, new permission, spending threshold and purchase flow as a product/policy change.

Signed AAB creation happens only in a protected environment with separate privileges/Play Console operator. A PM approves exact commit + version and receives checksum and track evidence. Upload keys stay outside source; Google Play App Signing manages the app signing process. Untrusted PRs must not access release credentials.

## 4. Required PM gates — from idea to published Android app

| Stage | Evidence D1–D5 must supply | Final manager action |
|---|---|---|
| **G0 Product discovery** | Audience, specific need, clickable sample, differentiated feature feasibility spike, MVP/non-goals, data/rights risks | PM signs v1 scope and budget |
| **G1 Buildable framework** | Root Gradle wrapper, compiling app module, D2 contracts with fakes, D3 schema design, D4 technical spike, D5 green CI | PM accepts modules and independent file ownership |
| **G2 Vertical slice** | Real imported/user input → D1 screen → D2 logic → D3 persistence → D4 actual processing → D5 tested Android output | PM watches real device demo and rejects stub flows |
| **G3 MVP feature complete** | Traceable acceptance matrix; all expected flows and recovery; security/privacy review; assets and licenses; QA automation | PM freezes scope and starts stabilization |
| **G4 Release candidate** | Exact tested SHA/version, lint/unit/UI/device/benchmark reports, all blocker issues closed, screenshots match binary | PM formally approves RC or records HOLD |
| **G5 Play internal/closed test** | Verified package/signing/target API/data-safety declarations, testers' installation/upgrade feedback, test-track URL | PM directs required fixes or authorizes launch candidate |
| **G6 Staged production** | Approved signed AAB, store listing, rollout percentages and stop conditions, support contact, crash/error monitoring | PM + privileged operator publish through Play |
| **G7 Operate** | Review sentiment, active retention, core funnel, crash/ANR, cost per active user, incident and support log | PM prioritizes fixes and measures product-market signal |

### 4.1 Project manager's ten-step finalization runbook — perform separately for each of the ten

1. **Inventory** all merged PRs, unresolved tickets, deferred features and interface versions; record role owner attestations from D1–D5 for the same commit, not five unrelated branch heads.
2. **Freeze** feature scope, schema, permissions, media assets, build tools and package/version metadata; capture immutable SHA, test environment and backend configuration ID.
3. **Reproduce** a cold-install first-user journey personally. If an advertised core feature is mocked, manually faked, missing or non-functional, STOP and return to the responsible developer.
4. **Demand independent proof** of static checks, unit tests, integration/Android tests and physical device flows from D5 plus appropriate domain/engine author; require real actual outputs (MP4/sticker/song/route/split), not screenshots of an editor.
5. **Run product-specific threat review** with D3/D5. Check permissions, sharing links, sensitive-data handling, deletion, asset licenses, accessibility and payment/ads rules if present. A missing or inaccurate Data safety declaration blocks release.
6. **Resolve defects.** P0 (user security/data loss) or P1 (core functionality/crash) blocks publication. P2 is deferrable only with user impact, workaround and accountable issue; privacy violations cannot be relabeled P2.
7. **Prepare store assets.** PM signs truthful short/long descriptions, images from the accepted build, demo video, icon, content rating, privacy policy, support link, pricing, monetization and app availability settings.
8. **Package securely.** Release operator produces signed AAB from the immutable SHA and records SHA-256, versionName/code, signing/upload key controls, Play internal/closed track installation and upgrade reports. PM verifies resulting artifact and account permissions.
9. **Roll out cautiously.** Define telemetry guardrails and pause criteria in advance; stagger rollout, review crash/performance/battery/API costs and stop on regressions. Server-side disabling is possible only if actually implemented; otherwise publish a repaired AAB.
10. **Close and operate.** Tag `{slug}/v0.1.0` for an actually published release, write changelog, archive QA/rights/security/store evidence under `apps/{slug}/docs/releases/{version}/`, and run 7/30-day review. Never tag an unfinished app as released.

**Decision rights:** PM owns product scope, prioritization, accept/reject and Play rollout; D1 owns presentation, D2 feature rule correctness, D3 privacy/storage/network, D4 algorithm/media quality, D5 platform/QA. Any engineer can block a release for credible security/privacy violation requiring independent review. PMs must not override failing CI as routine schedule pressure.

## 5. App-specific squad assignments and independent PM release controls

All paths in each section are relative to `apps/{slug}/`. The five developers **work at the same time** from agreed interfaces rather than waiting for one developer to finish the entire app. The PM has no separate developer seat. Each listed verification is **required future evidence**, not an assertion it currently passed.

### 5.1 StickerJet — six-seat squad SJ

**First-user promise:** Convert short personal videos into original animated stickers with reliable sharing. **Audience:** chat groups and creators. **North-star metric:** successful exports shared per active creator. **Source root:** `apps/stickerjet/`. **Team:** SJ-D1, SJ-D2, SJ-D3, SJ-D4, SJ-D5 and SJ-PM.

**MVP scope:** 1–2-second import/capture; trim/loop; assisted background removal with manual fix; caption and preview; proven Android sharing/export formats. **Explicitly excluded:** unlimited cloud AI, guaranteed pet/object segmentation, public social network.

**SJ-D1 — UI developer.** Writes `src/main/kotlin/com/agency/stickerjet/ui/** and src/main/res/**` within the StickerJet module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Compose capture/import/edit/preview/export screens; timeline gestures, undo, TalkBack and empty/error/loading states. **Required test evidence:** Import-to-share UI robot tests and real screenshot set. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**SJ-D2 — Domain developer.** Writes `src/main/kotlin/com/agency/stickerjet/domain/** and src/test/**` within the StickerJet module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** ClipProject/TrimRange/StickerPreset models; frame and duration validation; export capability contracts; deterministic filename/version rules. **Required test evidence:** Unit tests for trim boundaries, format constraints, undo/redo and duplicate names. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**SJ-D3 — Data developer.** Writes `src/main/kotlin/com/agency/stickerjet/data/** and src/test/**` within the StickerJet module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Private draft persistence; URI grants; export history and cleanup; optional messenger metadata adapter; no unnecessary user uploads. **Required test evidence:** Tests for media revocation, disk full, deletion, app restart and share-URI expiry. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**SJ-D4 — Engine developer.** Writes `src/main/kotlin/com/agency/stickerjet/engine/**, assets/source/**, benchmarks/**` within the StickerJet module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Codec/frame extraction; foreground matting only for supported content; alpha compositing, loop timing, caption compositing and encode fallback. **Required test evidence:** Golden frames for edge halos and timing; codec compatibility and peak-memory benchmarks. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**SJ-D5 — Android platform & independent QA developer.** Writes `src/main/kotlin/com/agency/stickerjet/platform/**, src/androidTest/**, docs/qa-test-plan.md` within the StickerJet module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Scoped media/camera permissions; content-provider share intents; Android lifecycle; CI/device matrix and end-to-end test harness. **Required test evidence:** Physical tests on two Android versions and target messenger formats; graceful unsupported export. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**SJ-PM — unique manager for this app.** Owns `docs/product.md`, `docs/release-checklist.md`, `docs/decisions.md`, `docs/releases/**` and `store/**`. This manager performs these distinct finalization duties:

1. Time-box a feasibility spike on animated sticker packaging and actual messenger compatibility; do not promise unsupported imports.
2. Accept full original-video-to-share demonstration on a physical device, including failed export and manual segmentation correction.
3. Review user-media retention, licensing for included art, Play photo/video permission rationale and accurate store screenshots.
4. Freeze tested export formats, own AAB/store rollout and monitor successful recipient-side sharing.
5. Confirm all five role PRs are integrated at the **same** immutable SHA; demand D5's full instrumented/device evidence, run the app's value journey independently, freeze release scope, approve listing/policy assets, then complete G4→G5→G6 via authorized signing and Google Play operators. Record app-only release tag and G7 support results.

**App-specific non-negotiable acceptance:** 1) Imported clips can be corrected, exported and shared without account creation. 2) A failure does not destroy the draft. 3) Claimed integrations are validated on recipient apps.

**Risk gate:** Automatic segmentation may fail on pets/objects; messenger animated sticker API formats can change. Owner: D3/D4 for technical controls, D5 for verification, PM for user claims and release decision.

**Proposed privacy-reviewed telemetry:** `import_started`, `trim_applied`, `render_success`, `share_invoked`, `export_error`. These are intended aggregate event names, not permission to record user media or identity. D3 validates fields, D5 verifies, PM approves definitions.

**First G2 demonstration:** five functional lanes connected into a real installable path that delivers the promised output or outcome, including at least one denied-permission or failure recovery case; no mocked engine, fabricated votes, static image instead of video, or inactive share button can pass. The PM approves only evidenced operation on a real suitable phone.

### 5.2 TimeFlip — six-seat squad TF

**First-user promise:** Create visually smooth before/after transformation videos from two user photos. **Audience:** artists, renovators and progress diarists. **North-star metric:** completed shareable videos per active creator. **Source root:** `apps/timeflip/`. **Team:** TF-D1, TF-D2, TF-D3, TF-D4, TF-D5 and TF-PM.

**MVP scope:** two-photo import; overlay alignment; safe auto-align plus manual transform; wipe/dissolve reveal; MP4 export; Android share. **Explicitly excluded:** music licensing catalogue, cloud galleries, photogrammetry.

**TF-D1 — UI developer.** Writes `src/main/kotlin/com/agency/timeflip/ui/** and src/main/res/**` within the TimeFlip module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Two-photo wizard, draggable before/after slider, alignment controls, transition presets and export progress with cancel. **Required test evidence:** Usability tests with mixed orientations and large fonts; screenshot/golden UI tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**TF-D2 — Domain developer.** Writes `src/main/kotlin/com/agency/timeflip/domain/** and src/test/**` within the TimeFlip module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** ImagePair, RegistrationTransform, Transition and OutputProfile; nondestructive history and aspect-safe math. **Required test evidence:** Pure tests of transform composition, undo/redo, crop areas and invalid dimensions. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**TF-D3 — Data developer.** Writes `src/main/kotlin/com/agency/timeflip/data/** and src/test/**` within the TimeFlip module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Save photo URI references and normalized project metadata; thumbnail cache, restore-on-restart and output cleanup. **Required test evidence:** Revoked URI, deleted original, low-storage and migration tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**TF-D4 — Engine developer.** Writes `src/main/kotlin/com/agency/timeflip/engine/**, assets/source/**, benchmarks/**` within the TimeFlip module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Feature-based alignment with confidence fallback to user controls; frame interpolation and local video encoding. **Required test evidence:** Golden motion tests, playable 30fps render, aspect/rotation checks and memory profiling. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**TF-D5 — Android platform & independent QA developer.** Writes `src/main/kotlin/com/agency/timeflip/platform/**, src/androidTest/**, docs/qa-test-plan.md` within the TimeFlip module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Photo Picker, scoped export/share, media encoder capability detection and end-to-end tests. **Required test evidence:** Real device import-to-export regression; background interruption and cancellation cases. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**TF-PM — unique manager for this app.** Owns `docs/product.md`, `docs/release-checklist.md`, `docs/decisions.md`, `docs/releases/**` and `store/**`. This manager performs these distinct finalization duties:

1. Choose an initial narrow creator audience, collect five real before/after jobs and state the promise precisely.
2. Reject jumpy transitions, distorted crops or incorrect orientation after inspecting actual exported MP4s.
3. Approve on-device processing claims, free/pro feature split and current store assets.
4. Sign off the tested commit and monitor export completion rather than just installations.
5. Confirm all five role PRs are integrated at the **same** immutable SHA; demand D5's full instrumented/device evidence, run the app's value journey independently, freeze release scope, approve listing/policy assets, then complete G4→G5→G6 via authorized signing and Google Play operators. Record app-only release tag and G7 support results.

**App-specific non-negotiable acceptance:** 1) First-time user gets a playable correctly oriented MP4 without signup. 2) Auto-align has a usable manual fallback. 3) Exports play in popular media players.

**Risk gate:** Large inputs and heavily changed scenes cause poor automatic alignment; manual correction is mandatory. Owner: D3/D4 for technical controls, D5 for verification, PM for user claims and release decision.

**Proposed privacy-reviewed telemetry:** `pair_imported`, `align_corrected`, `export_done`, `export_cancelled`, `shared`. These are intended aggregate event names, not permission to record user media or identity. D3 validates fields, D5 verifies, PM approves definitions.

**First G2 demonstration:** five functional lanes connected into a real installable path that delivers the promised output or outcome, including at least one denied-permission or failure recovery case; no mocked engine, fabricated votes, static image instead of video, or inactive share button can pass. The PM approves only evidenced operation on a real suitable phone.

### 5.3 PhotoDuel — six-seat squad PD

**First-user promise:** Run private photo challenges with friend invitations and fair voting. **Audience:** small friend groups and clubs. **North-star metric:** weekly challenges with two or more participating members. **Source root:** `apps/photoduel/`. **Team:** PD-D1, PD-D2, PD-D3, PD-D4, PD-D5 and PD-PM.

**MVP scope:** private rooms; bounded invite links; photo submissions; deadline and voting; server-authoritative results; report/block/delete. **Explicitly excluded:** public discovery feeds, chat, unrestricted UGC and open-age social network.

**PD-D1 — UI developer.** Writes `src/main/kotlin/com/agency/photoduel/ui/** and src/main/res/**` within the PhotoDuel module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Room create/join screens; challenge timer; submit/vote/results flow and abuse-report actions with onboarding. **Required test evidence:** Two-user accessible end-to-end navigation and expired-invite UX checks. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**PD-D2 — Domain developer.** Writes `src/main/kotlin/com/agency/photoduel/domain/** and src/test/**` within the PhotoDuel module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Challenge, Membership, Vote and TieBreak state machines; vote eligibility, deadlines, clock drift and rule enforcement. **Required test evidence:** Invariant tests for one vote per member, no late submission and deterministic ties. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**PD-D3 — Data developer.** Writes `src/main/kotlin/com/agency/photoduel/data/** and src/test/**` within the PhotoDuel module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Server auth/authorization, image storage, expiring invite tokens, idempotent voting endpoint and deletion/retention policy. **Required test evidence:** Adversarial tests for cross-room access, concurrent votes, revoked membership and bandwidth costs. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**PD-D4 — Engine developer.** Writes `src/main/kotlin/com/agency/photoduel/engine/**, assets/source/**, benchmarks/**` within the PhotoDuel module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Photo resizing, thumbnail, metadata stripping and low-bandwidth upload; themed prompt display pipeline. **Required test evidence:** Image fixtures across formats/EXIF, memory limit and slow-upload cancel/retry. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**PD-D5 — Android platform & independent QA developer.** Writes `src/main/kotlin/com/agency/photoduel/platform/**, src/androidTest/**, docs/qa-test-plan.md` within the PhotoDuel module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Camera/picker/deep link and optional notification adapters; multi-account instrumented tests and abuse testing. **Required test evidence:** Two-device submission/vote/results regression, deny-access and moderation escalation proof. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**PD-PM — unique manager for this app.** Owns `docs/product.md`, `docs/release-checklist.md`, `docs/decisions.md`, `docs/releases/**` and `store/**`. This manager performs these distinct finalization duties:

1. Do a substantive moderation, user-generated-content, privacy and age-positioning review before beta.
2. Approve cost/retention budgets and an incident owner for complaints; never launch anonymous public feed without moderation.
3. Require two-account proof that server prevents double votes and leaks no private room data.
4. Use closed groups to validate invitations leading to participation, then decide staged publishing.
5. Confirm all five role PRs are integrated at the **same** immutable SHA; demand D5's full instrumented/device evidence, run the app's value journey independently, freeze release scope, approve listing/policy assets, then complete G4→G5→G6 via authorized signing and Google Play operators. Record app-only release tag and G7 support results.

**App-specific non-negotiable acceptance:** 1) Only entitled room members view group media. 2) Votes reconcile under retries and simultaneous voting. 3) Reports, blocks and deletion controls function.

**Risk gate:** UGC safety/moderation, data retention and backend abuse are launch blockers. Owner: D3/D4 for technical controls, D5 for verification, PM for user claims and release decision.

**Proposed privacy-reviewed telemetry:** `room_created`, `invite_opened`, `submitted`, `vote_cast`, `completed_challenge`. These are intended aggregate event names, not permission to record user media or identity. D3 validates fields, D5 verifies, PM approves definitions.

**First G2 demonstration:** five functional lanes connected into a real installable path that delivers the promised output or outcome, including at least one denied-permission or failure recovery case; no mocked engine, fabricated votes, static image instead of video, or inactive share button can pass. The PM approves only evidenced operation on a real suitable phone.

### 5.4 RetroWorld — six-seat squad RW

**First-user promise:** Transform photos into distinct original retro-game-inspired visuals and short clips. **Audience:** retro-game fans, photographers and video creators. **North-star metric:** exports shared per weekly creator. **Source root:** `apps/retroworld/`. **Team:** RW-D1, RW-D2, RW-D3, RW-D4, RW-D5 and RW-PM.

**MVP scope:** three distinctive original retro presets; GPU pixelation/palette/dither; fictional HUD editor; non-destructive compare; photo/clip export. **Explicitly excluded:** full 3D world reconstruction, brand logo copying, paid cloud generation by default.

**RW-D1 — UI developer.** Writes `src/main/kotlin/com/agency/retroworld/ui/** and src/main/res/**` within the RetroWorld module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Preset gallery, split before/after view, editing sliders, fictional HUD controls and export/share. **Required test evidence:** TalkBack for effects and non-gesture alternatives; screenshot quality tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**RW-D2 — Domain developer.** Writes `src/main/kotlin/com/agency/retroworld/domain/** and src/test/**` within the RetroWorld module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Typed effect graph, safe ranges, layer ordering, presets and non-destructive revision model. **Required test evidence:** Serialization, graph determinism and effect parameter bounds tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**RW-D3 — Data developer.** Writes `src/main/kotlin/com/agency/retroworld/data/** and src/test/**` within the RetroWorld module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** On-device project/preset storage and thumbnails; source media grants, local export ledger and cache management. **Required test evidence:** Recovery, source revocation, migration and cache eviction tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**RW-D4 — Engine developer.** Writes `src/main/kotlin/com/agency/retroworld/engine/**, assets/source/**, benchmarks/**` within the RetroWorld module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** GPU shader passes for dither, quantization, palette remap, pixel-grid and pseudo-atmosphere; short pan encoder. **Required test evidence:** Golden images for varied scenes and GPU/thermal benchmark on different chipsets. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**RW-D5 — Android platform & independent QA developer.** Writes `src/main/kotlin/com/agency/retroworld/platform/**, src/androidTest/**, docs/qa-test-plan.md` within the RetroWorld module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** GPU capability handling, photo access, high-memory input downsample, saved-media share and UI integration suite. **Required test evidence:** Mali/Adreno compatibility and GPU failure fallback on physical devices. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**RW-PM — unique manager for this app.** Owns `docs/product.md`, `docs/release-checklist.md`, `docs/decisions.md`, `docs/releases/**` and `store/**`. This manager performs these distinct finalization duties:

1. Commission a recognizable original visual art bible and explicit baseline reference images.
2. Test 20 varied photos; reject presets indistinguishable from standard filters.
3. Check third-party licenses and avoid console/game trademarks in UI and marketing.
4. Set cloud-processing cost to zero in first MVP unless evidence proves necessary; accept exact exported artwork.
5. Confirm all five role PRs are integrated at the **same** immutable SHA; demand D5's full instrumented/device evidence, run the app's value journey independently, freeze release scope, approve listing/policy assets, then complete G4→G5→G6 via authorized signing and Google Play operators. Record app-only release tag and G7 support results.

**App-specific non-negotiable acceptance:** 1) Each preset is recognizable and stable across a broad photo set. 2) No image is silently uploaded. 3) Unsupported GPU paths show a safe fallback.

**Risk gate:** Novel visual identity is harder than routine pixel filters; avoid infringing art and marks. Owner: D3/D4 for technical controls, D5 for verification, PM for user claims and release decision.

**Proposed privacy-reviewed telemetry:** `preset_selected`, `preview_ok`, `export_done`, `shared`, `gpu_error`. These are intended aggregate event names, not permission to record user media or identity. D3 validates fields, D5 verifies, PM approves definitions.

**First G2 demonstration:** five functional lanes connected into a real installable path that delivers the promised output or outcome, including at least one denied-permission or failure recovery case; no mocked engine, fabricated votes, static image instead of video, or inactive share button can pass. The PM approves only evidenced operation on a real suitable phone.

### 5.5 FluidOS — six-seat squad FO

**First-user promise:** Create beautiful interactive live wallpapers that respect phone battery and lifecycle. **Audience:** Android personalization enthusiasts. **North-star metric:** wallpaper activations retained after seven days. **Source root:** `apps/fluidos/`. **Team:** FO-D1, FO-D2, FO-D3, FO-D4, FO-D5 and FO-PM.

**MVP scope:** three original visual themes; touch/tilt interaction; WallpaperService activation; quality controls; low-power fallback. **Explicitly excluded:** always-listening mic, compulsory network access and online asset store.

**FO-D1 — UI developer.** Writes `src/main/kotlin/com/agency/fluidos/ui/** and src/main/res/**` within the FluidOS module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Live preview, theme gallery, sliders, set-wallpaper helper and battery-mode explainer. **Required test evidence:** Accessibility and return-from-wallpaper-picker UI tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**FO-D2 — Domain developer.** Writes `src/main/kotlin/com/agency/fluidos/domain/** and src/test/**` within the FluidOS module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** ThemePreset, MotionSettings and QualityPolicy models; safe ranges, deterministic seeds and state transitions. **Required test evidence:** Preference and quality-tier unit tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**FO-D3 — Data developer.** Writes `src/main/kotlin/com/agency/fluidos/data/** and src/test/**` within the FluidOS module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Versioned local theme and palette preferences, settings backup choice and migration. **Required test evidence:** Corrupted preferences and process restart tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**FO-D4 — Engine developer.** Writes `src/main/kotlin/com/agency/fluidos/engine/**, assets/source/**, benchmarks/**` within the FluidOS module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** WallpaperService-compatible GPU particle/flow rendering with visibility-aware pause and stable frame pacing. **Required test evidence:** Battery/thermal, frame budget, memory and touch latency benchmarks. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**FO-D5 — Android platform & independent QA developer.** Writes `src/main/kotlin/com/agency/fluidos/platform/**, src/androidTest/**, docs/qa-test-plan.md` within the FluidOS module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Service lifecycle, rotation/lock screen, power modes, sensor adapters, OS compatibility CI. **Required test evidence:** Screen on/off/lock/unlock and OEM wallpaper picker regression. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**FO-PM — unique manager for this app.** Owns `docs/product.md`, `docs/release-checklist.md`, `docs/decisions.md`, `docs/releases/**` and `store/**`. This manager performs these distinct finalization duties:

1. Require actual energy-impact evidence on both a lower-cost and a modern phone.
2. Reject themes which obscure icons or make the device hot; approve a low-power mode by default.
3. Approve permission and battery claims; prohibit unneeded background work.
4. Check long-duration wallpaper activation and stability before publishing.
5. Confirm all five role PRs are integrated at the **same** immutable SHA; demand D5's full instrumented/device evidence, run the app's value journey independently, freeze release scope, approve listing/policy assets, then complete G4→G5→G6 via authorized signing and Google Play operators. Record app-only release tag and G7 support results.

**App-specific non-negotiable acceptance:** 1) Visibility pause stops continuous full-rate rendering. 2) Picker, orientation and lock state recover correctly. 3) Static/reduced-motion fallback is available.

**Risk gate:** WallpaperService OEM fragmentation and GPU battery draw. Owner: D3/D4 for technical controls, D5 for verification, PM for user claims and release decision.

**Proposed privacy-reviewed telemetry:** `preset_previewed`, `wallpaper_set`, `low_power_selected`, `quality_changed`, `renderer_crash`. These are intended aggregate event names, not permission to record user media or identity. D3 validates fields, D5 verifies, PM approves definitions.

**First G2 demonstration:** five functional lanes connected into a real installable path that delivers the promised output or outcome, including at least one denied-permission or failure recovery case; no mocked engine, fabricated votes, static image instead of video, or inactive share button can pass. The PM approves only evidenced operation on a real suitable phone.

### 5.6 SnapBrain — six-seat squad SB

**First-user promise:** Search text inside consented screenshots using private, on-device OCR. **Audience:** students and people with many screenshots. **North-star metric:** search result openings per weekly active user. **Source root:** `apps/snapbrain/`. **Team:** SB-D1, SB-D2, SB-D3, SB-D4, SB-D5 and SB-PM.

**MVP scope:** explicit screenshot selection or eligible limited media access; on-device OCR; local index; text search/filter; delete and reindex. **Explicitly excluded:** all-files storage grants without policy basis, background cloud OCR, private data sharing.

**SB-D1 — UI developer.** Writes `src/main/kotlin/com/agency/snapbrain/ui/** and src/main/res/**` within the SnapBrain module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Consent and privacy tour, scan progress, search, highlighted results, deletion and clear no-results state. **Required test evidence:** TalkBack, partial permissions and multilingual UI tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**SB-D2 — Domain developer.** Writes `src/main/kotlin/com/agency/snapbrain/domain/** and src/test/**` within the SnapBrain module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** IndexJob, OCRBlock, ScreenshotDocument and SearchQuery; ranking, duplicate detection and cancellation. **Required test evidence:** Search correctness, text normalization, locale ordering and index lifecycle tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**SB-D3 — Data developer.** Writes `src/main/kotlin/com/agency/snapbrain/data/** and src/test/**` within the SnapBrain module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Protected app-private OCR DB, URI and thumbnail pointers, transactional indexing and wipe/reindex flows. **Required test evidence:** Schema migration, permission revocation, data deletion and offline persistence tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**SB-D4 — Engine developer.** Writes `src/main/kotlin/com/agency/snapbrain/engine/**, assets/source/**, benchmarks/**` within the SnapBrain module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** On-device OCR and text bounding boxes, batched memory-capped processing and background job throttling. **Required test evidence:** Accuracy/throughput on labeled consented fixtures, heat and battery benchmarking. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**SB-D5 — Android platform & independent QA developer.** Writes `src/main/kotlin/com/agency/snapbrain/platform/**, src/androidTest/**, docs/qa-test-plan.md` within the SnapBrain module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Photo Picker or eligible scoped permission, WorkManager scheduling, denial flows and no-network verification. **Required test evidence:** Tests prove no unwanted screenshot bytes transmitted and no blanket all-files permission. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**SB-PM — unique manager for this app.** Owns `docs/product.md`, `docs/release-checklist.md`, `docs/decisions.md`, `docs/releases/**` and `store/**`. This manager performs these distinct finalization duties:

1. Complete a screenshot threat model covering IDs, chats, bookings and financial details.
2. Verify eligibility under current Play photo/media permission policy; prefer explicitly selected content.
3. Demand offline search and deletion demos with no hidden data transmissions.
4. Approve only privacy claims supported by instrumentation and publish limited-access behavior clearly.
5. Confirm all five role PRs are integrated at the **same** immutable SHA; demand D5's full instrumented/device evidence, run the app's value journey independently, freeze release scope, approve listing/policy assets, then complete G4→G5→G6 via authorized signing and Google Play operators. Record app-only release tag and G7 support results.

**App-specific non-negotiable acceptance:** 1) Offline indexed search returns relevant screenshots. 2) Index and source access can be removed by the user. 3) Partial grants work without forced full-library access.

**Risk gate:** OCR databases contain sensitive content; Play media permission compliance must be reviewed. Owner: D3/D4 for technical controls, D5 for verification, PM for user claims and release decision.

**Proposed privacy-reviewed telemetry:** `index_opt_in`, `index_finished`, `search_run`, `result_opened`, `index_deleted`. These are intended aggregate event names, not permission to record user media or identity. D3 validates fields, D5 verifies, PM approves definitions.

**First G2 demonstration:** five functional lanes connected into a real installable path that delivers the promised output or outcome, including at least one denied-permission or failure recovery case; no mocked engine, fabricated votes, static image instead of video, or inactive share button can pass. The PM approves only evidenced operation on a real suitable phone.

### 5.7 BeatBloom — six-seat squad BB

**First-user promise:** Turn a hum, whistle or tap into a short editable music arrangement. **Audience:** musicians and casual short-form creators. **North-star metric:** saved/exported original song loops per active creator. **Source root:** `apps/beatbloom/`. **Team:** BB-D1, BB-D2, BB-D3, BB-D4, BB-D5 and BB-PM.

**MVP scope:** short mic/tap capture; pitch/onset estimation; manual note correction; original drum/bass/pad arrangement; WAV/AAC export. **Explicitly excluded:** voice cloning, studio-quality transcription promises, unlicensed loops or music catalogue.

**BB-D1 — UI developer.** Writes `src/main/kotlin/com/agency/beatbloom/ui/** and src/main/res/**` within the BeatBloom module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Capture/tap, metronome, confidence and pitch correction UI, arrangement and export visualizer. **Required test evidence:** Permissions denied, noisy input, accessibility and note edit UX tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**BB-D2 — Domain developer.** Writes `src/main/kotlin/com/agency/beatbloom/domain/** and src/test/**` within the BeatBloom module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** PitchEvent, TempoGrid, SongPattern and Arrangement models; quantization, key mapping and note edit history. **Required test evidence:** Tempo, transposition, off-grid input and note boundary unit tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**BB-D3 — Data developer.** Writes `src/main/kotlin/com/agency/beatbloom/data/** and src/test/**` within the BeatBloom module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Local audio clips, music-event projects, generated sample metadata and export catalog. **Required test evidence:** Storage/restart recovery, deletion, media-file validity and original sound license records. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**BB-D4 — Engine developer.** Writes `src/main/kotlin/com/agency/beatbloom/engine/**, assets/source/**, benchmarks/**` within the BeatBloom module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Mic DSP pitch/onset analysis, synthesis/sampler timing, mix/render pipeline, efficient visualizer. **Required test evidence:** Labeled audio pitch accuracy, latency, clipping, jitter and cross-device export checks. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**BB-D5 — Android platform & independent QA developer.** Writes `src/main/kotlin/com/agency/beatbloom/platform/**, src/androidTest/**, docs/qa-test-plan.md` within the BeatBloom module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Microphone permission, audio focus, Bluetooth/headphone routes, interruptions, sample-rate test matrix. **Required test evidence:** Phone call, focus loss, route change and low-end device automated/manual regressions. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**BB-PM — unique manager for this app.** Owns `docs/product.md`, `docs/release-checklist.md`, `docs/decisions.md`, `docs/releases/**` and `store/**`. This manager performs these distinct finalization duties:

1. Market arrangement assistance rather than flawless transcription; run a diverse sample listening review.
2. Approve wholly original or cleared sounds and explicit attribution for bundled libraries.
3. Prove the manual note editor can repair inaccurate transcription without re-recording.
4. Inspect actual exported audio and signed app performance on several real microphone setups.
5. Confirm all five role PRs are integrated at the **same** immutable SHA; demand D5's full instrumented/device evidence, run the app's value journey independently, freeze release scope, approve listing/policy assets, then complete G4→G5→G6 via authorized signing and Google Play operators. Record app-only release tag and G7 support results.

**App-specific non-negotiable acceptance:** 1) A first timer creates playable audio from a short hum or beat. 2) Errors can be edited before export. 3) No uncleared samples ship.

**Risk gate:** Pitch quality in noisy rooms and DSP latency on mobile hardware. Owner: D3/D4 for technical controls, D5 for verification, PM for user claims and release decision.

**Proposed privacy-reviewed telemetry:** `capture_started`, `pitch_corrected`, `loop_played`, `export_done`, `audio_error`. These are intended aggregate event names, not permission to record user media or identity. D3 validates fields, D5 verifies, PM approves definitions.

**First G2 demonstration:** five functional lanes connected into a real installable path that delivers the promised output or outcome, including at least one denied-permission or failure recovery case; no mocked engine, fabricated votes, static image instead of video, or inactive share button can pass. The PM approves only evidenced operation on a real suitable phone.

### 5.8 BillFlash — six-seat squad BF

**First-user promise:** Scan a receipt, verify line items and calculate a transparent exact split. **Audience:** restaurant groups and travelers. **North-star metric:** correct splits shared with two or more participants. **Source root:** `apps/billflash/`. **Team:** BF-D1, BF-D2, BF-D3, BF-D4, BF-D5 and BF-PM.

**MVP scope:** receipt photo/OCR; mandatory item validation; people and shared items; tax/tip/discount choices; exact share summary. **Explicitly excluded:** bank access, payment processing and unverifiable automatic money movement.

**BF-D1 — UI developer.** Writes `src/main/kotlin/com/agency/billflash/ui/** and src/main/res/**` within the BillFlash module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Receipt scan, correction table, participant assignment, tip rules and final summary screens. **Required test evidence:** Readable decimal/currency UX and deliberate edit/confirm test. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**BF-D2 — Domain developer.** Writes `src/main/kotlin/com/agency/billflash/domain/** and src/test/**` within the BillFlash module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Money in integer minor units; split allocation, tax and tip bases, rounding/remainder invariant algorithms. **Required test evidence:** Property tests showing sum of all shares always equals confirmed total. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**BF-D3 — Data developer.** Writes `src/main/kotlin/com/agency/billflash/data/** and src/test/**` within the BillFlash module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Private receipt and item storage, optional expiring sharing, deletion, no bank credential collection. **Required test evidence:** Offline, deletion, share-link expiry and local schema migration tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**BF-D4 — Engine developer.** Writes `src/main/kotlin/com/agency/billflash/engine/**, assets/source/**, benchmarks/**` within the BillFlash module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** OCR, perspective correction, receipt line/price segmentation, totals confidence and human review cues. **Required test evidence:** Golden diverse receipt fixture suite and low-confidence behavior checks. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**BF-D5 — Android platform & independent QA developer.** Writes `src/main/kotlin/com/agency/billflash/platform/**, src/androidTest/**, docs/qa-test-plan.md` within the BillFlash module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Camera/Picker adapters, locale-sensitive currency, share sheet, end-to-end reconciliation suite. **Required test evidence:** Cross-locale decimal/currency, discounts, missing tax and split-shared-item tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**BF-PM — unique manager for this app.** Owns `docs/product.md`, `docs/release-checklist.md`, `docs/decisions.md`, `docs/releases/**` and `store/**`. This manager performs these distinct finalization duties:

1. Define launch currency/tax jurisdictions; do not silently assume one tax system.
2. Require every parsed receipt to be editable and reconcilable before a split is final.
3. Verify sharing privacy/expiry and ban unsupported payment promises.
4. Approve independent cent-perfect test corpus and monitor correction rates, not just scan volume.
5. Confirm all five role PRs are integrated at the **same** immutable SHA; demand D5's full instrumented/device evidence, run the app's value journey independently, freeze release scope, approve listing/policy assets, then complete G4→G5→G6 via authorized signing and Google Play operators. Record app-only release tag and G7 support results.

**App-specific non-negotiable acceptance:** 1) Participant shares sum precisely to bill total. 2) Users may override all misread OCR values. 3) No payment credentials or funds are processed.

**Risk gate:** OCR numbers are unreliable; financial arithmetic and transparent confirmation are non-negotiable. Owner: D3/D4 for technical controls, D5 for verification, PM for user claims and release decision.

**Proposed privacy-reviewed telemetry:** `receipt_scanned`, `line_corrected`, `split_verified`, `summary_shared`, `totals_mismatch`. These are intended aggregate event names, not permission to record user media or identity. D3 validates fields, D5 verifies, PM approves definitions.

**First G2 demonstration:** five functional lanes connected into a real installable path that delivers the promised output or outcome, including at least one denied-permission or failure recovery case; no mocked engine, fabricated votes, static image instead of video, or inactive share button can pass. The PM approves only evidenced operation on a real suitable phone.

### 5.9 PocketCompanion — six-seat squad PC

**First-user promise:** Grow an original tiny creature by completing focus sessions and optional healthy routines. **Audience:** students and people building focus habits. **North-star metric:** completed focus sessions per weekly active user. **Source root:** `apps/pocketcompanion/`. **Team:** PC-D1, PC-D2, PC-D3, PC-D4, PC-D5 and PC-PM.

**MVP scope:** creature selection; reliable focus timer; fair progression; habitat cosmetics; voluntary reminders; offline save. **Explicitly excluded:** health claims, manipulative streak penalties, real-money trading and mandatory sensor data.

**PC-D1 — UI developer.** Writes `src/main/kotlin/com/agency/pocketcompanion/ui/** and src/main/res/**` within the PocketCompanion module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Creature home, timer, rewards, habitat editor and gentle onboarding. **Required test evidence:** Accessibility, reduced motion and no-dark-pattern UX checks. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**PC-D2 — Domain developer.** Writes `src/main/kotlin/com/agency/pocketcompanion/domain/** and src/test/**` within the PocketCompanion module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Timer state machine, rewards, economy caps, local time/DST and streak policy. **Required test evidence:** Elapsed time and interruption invariants; deterministic point awarding. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**PC-D3 — Data developer.** Writes `src/main/kotlin/com/agency/pocketcompanion/data/** and src/test/**` within the PocketCompanion module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Versioned save games, creature state, cosmetic inventory and restore/export. **Required test evidence:** Migration, corrupt save recovery and data deletion tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**PC-D4 — Engine developer.** Writes `src/main/kotlin/com/agency/pocketcompanion/engine/**, assets/source/**, benchmarks/**` within the PocketCompanion module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Original animated characters and low-footprint art pipeline; stateful animations and cosmetic placements. **Required test evidence:** Golden animation/layout fixtures, memory and frame-rate benchmarks. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**PC-D5 — Android platform & independent QA developer.** Writes `src/main/kotlin/com/agency/pocketcompanion/platform/**, src/androidTest/**, docs/qa-test-plan.md` within the PocketCompanion module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Notification opt-in, timer lifecycle, optional step integration only with consent, automation suite. **Required test evidence:** Doze/standby, reboot/kill, permission refusal and no-notification mode tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**PC-PM — unique manager for this app.** Owns `docs/product.md`, `docs/release-checklist.md`, `docs/decisions.md`, `docs/releases/**` and `store/**`. This manager performs these distinct finalization duties:

1. Reject reward systems that coerce notifications or purchases.
2. Ensure creatures, sounds and habitats are original and licensed.
3. Test timers after process death and daylight saving, and prohibit false completion credit.
4. Evaluate seven-day voluntary retention and avoid promising therapeutic outcomes.
5. Confirm all five role PRs are integrated at the **same** immutable SHA; demand D5's full instrumented/device evidence, run the app's value journey independently, freeze release scope, approve listing/policy assets, then complete G4→G5→G6 via authorized signing and Google Play operators. Record app-only release tag and G7 support results.

**App-specific non-negotiable acceptance:** 1) No imaginary progress is awarded on interruption. 2) Core experience works without extra permissions or account. 3) A person can export or clear saved data.

**Risk gate:** Daily engagement requires sustainable content and avoids manipulative habit design. Owner: D3/D4 for technical controls, D5 for verification, PM for user claims and release decision.

**Proposed privacy-reviewed telemetry:** `creature_created`, `focus_started`, `focus_completed`, `day_return`, `accessory_equipped`. These are intended aggregate event names, not permission to record user media or identity. D3 validates fields, D5 verifies, PM approves definitions.

**First G2 demonstration:** five functional lanes connected into a real installable path that delivers the promised output or outcome, including at least one denied-permission or failure recovery case; no mocked engine, fabricated votes, static image instead of video, or inactive share button can pass. The PM approves only evidenced operation on a real suitable phone.

### 5.10 WalkWorld — six-seat squad WW

**First-user promise:** Turn a consented walking route into an original shareable miniature animated world. **Audience:** walkers and travelers. **North-star metric:** route postcards completed and exported per active user. **Source root:** `apps/walkworld/`. **Team:** WW-D1, WW-D2, WW-D3, WW-D4, WW-D5 and WW-PM.

**MVP scope:** foreground opt-in route capture; pause/stop; simplified path into low-poly world; redact start/end; offline image/clip export. **Explicitly excluded:** secret background location, real-time follower tracking, public precise routes.

**WW-D1 — UI developer.** Writes `src/main/kotlin/com/agency/walkworld/ui/** and src/main/res/**` within the WalkWorld module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Clear recording consent, pause/stop map, miniature editor, sensitive endpoint preview and export. **Required test evidence:** Outdoor readability and privacy preview tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**WW-D2 — Domain developer.** Writes `src/main/kotlin/com/agency/walkworld/domain/** and src/test/**` within the WalkWorld module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Trackpoint, route segmentation, GPS outlier filtering, pause and distance calculations, redact radius. **Required test evidence:** Simulated drift/outlier, pause, no-GPS and redaction unit tests. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**WW-D3 — Data developer.** Writes `src/main/kotlin/com/agency/walkworld/data/** and src/test/**` within the WalkWorld module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Local track storage, cache and deletion, map data license controls, default no cloud uploading. **Required test evidence:** Tests for canceled recording, location revoke, data deletion and offline restore. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**WW-D4 — Engine developer.** Writes `src/main/kotlin/com/agency/walkworld/engine/**, assets/source/**, benchmarks/**` within the WalkWorld module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Procedural route extrusion, low-poly scenery, animated camera, texture budget and short clip encoder. **Required test evidence:** Golden route forms, geometry invariants, low-memory GPU stress. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**WW-D5 — Android platform & independent QA developer.** Writes `src/main/kotlin/com/agency/walkworld/platform/**, src/androidTest/**, docs/qa-test-plan.md` within the WalkWorld module (other test/assets paths are app-relative); coordinates cross-lane touches by a reviewed PR. **Assigned deliverables:** Foreground-location/service lifecycle where justified; GPS emulator scripts; permission and battery matrix. **Required test evidence:** Field tests for denied/approximate GPS, urban drift and stop-recording reliability. **Handoff:** a bounded PR with exact contracts used, failure/cancel/retry semantics, demonstrable output and current CI/test results. This developer does not finalize the app or sign its release.

**WW-PM — unique manager for this app.** Owns `docs/product.md`, `docs/release-checklist.md`, `docs/decisions.md`, `docs/releases/**` and `store/**`. This manager performs these distinct finalization duties:

1. Require route privacy preview that hides residential start/end where requested and makes sharing explicit.
2. Review location permission necessity, foreground-service declarations and map/tiles licensing.
3. Test recorded outdoor walking sessions and exact exported postcards on physical phones.
4. Approve publishing only if tracking fully stops and data deletion/retention are unambiguous.
5. Confirm all five role PRs are integrated at the **same** immutable SHA; demand D5's full instrumented/device evidence, run the app's value journey independently, freeze release scope, approve listing/policy assets, then complete G4→G5→G6 via authorized signing and Google Play operators. Record app-only release tag and G7 support results.

**App-specific non-negotiable acceptance:** 1) Walk recording and export work without forced cloud sync. 2) Route location endpoints can be redacted before sharing. 3) Stop and permission revocation end collection.

**Risk gate:** GPS drift, precise-route privacy leakage and proprietary map tile licensing. Owner: D3/D4 for technical controls, D5 for verification, PM for user claims and release decision.

**Proposed privacy-reviewed telemetry:** `track_completed`, `redaction_used`, `postcard_rendered`, `shared`, `gps_error`. These are intended aggregate event names, not permission to record user media or identity. D3 validates fields, D5 verifies, PM approves definitions.

**First G2 demonstration:** five functional lanes connected into a real installable path that delivers the promised output or outcome, including at least one denied-permission or failure recovery case; no mocked engine, fabricated votes, static image instead of video, or inactive share button can pass. The PM approves only evidenced operation on a real suitable phone.


## 6. App by app earliest actionable issue seeds

Each PM creates real GitHub issues (numbers assigned by GitHub; these identifiers are only convenient planning codes). Duplicate the following compact issue taxonomy under the ten prefixes `SJ`, `TF`, `PD`, `RW`, `FO`, `SB`, `BB`, `BF`, `PC`, `WW`:

| Planning ticket | Owner | Deliverable | Cross-lane dependency |
|---|---|---|---|
| `{ID}-001` | PM | Product spec, v1 non-goals, risk register, review roster | All developers align |
| `{ID}-010` | D2 | Typed domain contracts + fake adapters + unit tests | Unblocks D1/D3/D4/D5 |
| `{ID}-020` | D1 | Actual Compose navigation and first core-user journey | Integrates D2 fake ports |
| `{ID}-030` | D3 | Working storage or server-authoritative adapter, schema tests | Implements D2 repositories |
| `{ID}-040` | D4 | Hard differentiator spike with measurable output and fallback | Implements D2 engine port |
| `{ID}-050` | D5 | Android app shell/permissions/CI/test runner | Builds on D1–D4 contracts |
| `{ID}-060` | D5 | Integrated instrumented core journey | Requires real adapters |
| `{ID}-070` | PM | G2 vertical-slice acceptance and stability backlog | Checks exact integrated SHA |
| `{ID}-080` | All / PM signoff | G4 RC evidence and G5 Play internal qualification | No outstanding blockers |

### 6.1 Example concrete vertical-slice ticket set for TimeFlip

- `TF-010` D2: implement `domain/Transformation.kt`; accept only with unit-tested affine transforms and crop behavior.
- `TF-020` D1: create `ui/PairEditorScreen.kt`, `ui/ExportScreen.kt` and mocked state; accept only with accessibility and error states.
- `TF-030` D3: create `data/ProjectRepositoryImpl.kt`; accept only when photo references survive process death and missing URI grants are handled.
- `TF-040` D4: create `engine/ImageRegistration.kt` and `engine/VideoExporter.kt`; accept only with visual golden tests and verified MP4 output.
- `TF-050` D5: create `platform/MediaAccess.kt` and `androidTest/TimeFlipJourneyTest.kt`; accept only with denied/partial-access and import-to-export cases on a real supported device.
- `TF-070` PM: verify all five inputs at one immutable source SHA, inspect the actual exported MP4, then sign `docs/release-checklist.md` G2.

The other nine PMs use their app's unique domain, storage, engine, UI and OS work packages from section 5; no one should reuse TimeFlip implementation tickets blindly.

## 7. Coordinated calendar — ten squads independently running in parallel

| Phase / target window | PM | D1 UX | D2 Domain | D3 Data | D4 Engine | D5 Platform/QA |
|---|---|---|---|---|---|---|
| Day 0–2 | G0 charter, staffing, MVP/acceptance | wireframe and interaction tokens | typed ports & mocks | schema, auth/privacy risk | high-risk feasibility proof | toolchain, CI, device list |
| Week 1 | accept G1 after compiling build | Compose shell and fake screen flows | state machine and test cases | durable adapters | prototype engine | manifest, permissions, smoke |
| Week 2–3 | accept G2 real vertical slice | connect actual flows and design | close core rules | real persistence/backend | real processing/export | device integration, automation |
| Weeks 3–5 | scope and budget decisions | accessibility and UI polishing | edge cases and correctness | failures/migration/security | quality/performance | regression matrix |
| Weeks 5–8+ | G3/G4 freeze where feasible | genuine store screenshots | contract signoff | retention/consent signoff | final output/golden tests | physical QA/performance evidence |
| After G4 | G5/G6 go/no-go; G7 support | fix UX issues | fix domain bugs | fix storage/security issues | fix media bugs | qualify and monitor fixes |

These are planning windows only. PhotoDuel moderation/backend, BeatBloom DSP, StickerJet packaging, RetroWorld art direction and WalkWorld location privacy may need more technical spikes. **Never release by calendar alone.** Individual PMs can publish independently once their G5/G6 gates pass.

## 8. Release evidence packet — expected under each app

~~~text
apps/{slug}/docs/releases/v0.1.0/
├── manifest.md          # exact SHA, unique package, versionName/code, AAB checksum
├── feature-matrix.md    # every MVP criterion mapped to verified test and PR
├── architecture.md      # contracts, third-party libraries, rollback/migrations
├── security.md          # storage, permissions, SDK privacy review, incident owner
├── qa.md                # unit/device/upgrade/accessibility and bugs
├── performance.md       # measured devices, memory, battery, processing timings
├── assets.md            # provenance/licensing and store screenshots
├── play-console.md      # track, Data safety, review/testing, actual submission state
├── rollout.md           # staged percentage, guardrails and stop procedure
└── pm-signoff.md        # evidence links, decision and responsible approvers
~~~

**No fake release packet:** Mark all fields as PENDING until evidence exists. Real values are mandatory for candidate SHA, signing provenance, checksum, permissions, selected testers, test date/device/API and crash metrics. Do not commit APK/AAB files or user media. GitHub Actions artifacts or protected storage link to binaries.

### 8.1 Standard test vectors and guardrails

- UX: screen reader, minimum supported size, 200% fonts, reduced motion, clear permission-denial alternatives, no useless buttons or abandoned states.
- Correctness: idempotence and cancellation; domain invariants; database upgrade; process death; malformed media; absent network; no duplicate charges or fake success.
- Performance: record render/export duration, UI frame pacing, memory ceiling and thermal effect on a low-end and modern physical phone for graphics/media-heavy apps.
- Privacy and safety: scoped permissions; least sharing; deletion; no unnecessary contacts/photo/location scanning; child/UGC moderation policy for social apps.
- Play: current targetSdk requirement, app signing, privacy/ads/IAP declarations, 64-bit compatibility where required and accurate content rating.
- Support: crash response owner, safety email, release rollback/rebuild plan and transparent changelog.

## 9. Growth experiments, financial controls and ethical monetization

The first 10,000 installs are not a guarantee and should not be treated as the PM's only metric. Each `docs/metrics.md` must define first-session time-to-value, successful core action, voluntary sharing/invite conversion, D1/D7/D30 activity where relevant, store listing conversion, retention, crash-free sessions, cost per active user and net income after fees. Do not log raw receipts, recordings, screenshots, exact walking routes, or private group images in analytics. PMs define events precisely and sample with privacy safeguards.

Possible later premium offers: StickerJet sticker editing packs; TimeFlip transition themes; PhotoDuel cosmetic private-room themes; RetroWorld original effects; FluidOS wallpaper presets; SnapBrain offline organizational tools; BeatBloom original instruments/genres; BillFlash advanced convenience; PocketCompanion cosmetics; WalkWorld postcard landscapes. Confirm billing/policy requirements and real user demand before implementing purchases. Essential correction, deletion, privacy, safety and core accessibility must remain available for free.

Run small opt-in creator/user studies, analyze the observed completion funnel, iterate each app independently. Reject fake ratings, coercive notification prompts, copyrighted assets and undisclosed third-party media uploads.

## 10. Root dependencies, hazards and escalation

**Root gradle/build logic risk:** PM Council steward + affected D5 review, build all apps and record change in an architecture decision. **Interface conflicts:** D2 publishes vN interface + mocks before D1/D3/D4 pull code. **Secrets:** immediately revoke/rotate leaked credentials, create incident record and stop release. **User content/privacy:** D3/D5/PM require new threat assessment for new Android permissions and analytics fields. **Cost spikes:** D3 sets quotas and spend alerts before paid endpoints. **Accessibility/security defects:** P0/P1 blocks publication regardless of schedule. **Missing human staff:** PM marks seat as unfilled and revises capacity honestly.

## 11. Starting order after charter commit

1. Assign actual GitHub reviewers/managers and fill ten `TEAM.md` rosters or explicitly leave seats vacant; designate the rotating root steward.
2. Open G0 epics, contract issues and differentiated-engine feasibility spikes for all ten products.
3. Bootstrap Android toolchain, Gradle wrapper/version catalog and ten compilable modules in a reviewed root PR; configure path-filtered build/test CI.
4. Start D1–D5 role workstreams concurrently with mocked domain ports and owned folders; merge small PRs.
5. Require one real full device vertical slice per app at G2, then feature-complete G3, signed candidate G4, Play verification G5 and staged production G6 separately.
6. At G7 measure active users and reliability, refine products and fund continued maintenance; do not claim ten shipped apps until they exist.

## 12. Official reference links (recheck before publication)

- Android app modularization: https://developer.android.com/topic/modularization
- Android architecture: https://developer.android.com/topic/architecture
- Target API policy (Android 16/API 36 beginning 2026-08-31): https://support.google.com/googleplay/android-developer/answer/11926878
- Android Photo Picker: https://developer.android.com/training/data-storage/shared/photopicker
- Android app signing: https://developer.android.com/studio/publish/app-signing
- Google Play developer policies: https://support.google.com/googleplay/android-developer/topic/9858052
- CODEOWNERS: https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners

**Agency release rule:** Each of the ten products becomes 'finished' only when its own independent six-seat squad can demonstrate the promised functionality, current test/security/policy evidence and a PM-approved signed publication. A folder or a plan is not an installed app.
