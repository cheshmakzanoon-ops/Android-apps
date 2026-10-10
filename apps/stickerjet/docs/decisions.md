# StickerJet decision log

Format: one entry per decision, newest last. Each records context, the decision, and its consequences.

## ADR-001 — A pure JVM core module beside the Android module

**Context.** Domain rules, mask math and the animated-WebP writer must be tested without the Android
SDK, and must not import Android types (project-outline §1.1, D2 rule).

**Decision.** `:apps:stickerjet-core` is a Kotlin/JVM library in `apps/stickerjet/core/`, mapped to its
own Gradle path. The Android module `:apps:stickerjet` depends on it. The core holds `domain`, the
pure `engine` math and the WebP container writer. `apps/stickerjet/build.gradle.kts` is not
configured when `-Pstickerjet.skipAndroid=true` is passed (see `settings.gradle.kts`).

**Consequences.** Core tests run on any JDK 17+ machine. The core is one extra module, which is
justified by the enforced boundary, not by a wish for more modules.

## ADR-002 — Person-focused segmentation with ML Kit selfie segmentation

**Context.** The product needs on-device foreground removal. Candidate options: ML Kit selfie
segmentation (person), ML Kit subject segmentation (images, more subject types, but a different API
and beta status), or a custom model.

**Decision.** Use `com.google.mlkit:segmentation-selfie` in single-image mode with the raw-size mask.
It runs on device, bundles its model and needs no network. Non-person subjects are not segmented. A
frame without a confident person keeps its original background and the UI says so.

**Consequences.** Pets and objects are explicitly out of scope. The ML Kit model is an external,
licensed component and is listed in `assets/licenses/`. Segmentation quality has not been measured on
real human footage in this repository yet; that is an open verification item (docs/qa-test-plan.md).

## ADR-003 — Animated WebP from platform frames and a tested container writer

**Context.** `Bitmap.compress` writes static WebP only; the Android documentation for `CompressFormat`
describes single-bitmap compression, and `WEBP_LOSSY` requires API 30 (the legacy `WEBP` value is
deprecated). Animated WebP requires the RIFF `VP8X`/`ANIM`/`ANMF` container.

**Decision.** Encode each composed frame as a static WebP with `Bitmap.compress` (`WEBP_LOSSY` on API
30+, legacy `WEBP` below). Mux the frame bitstreams into an animated container with
`AnimatedWebpWriter`, which is pure Kotlin and unit-tested. Every output is checked by an independent
decoder (Pillow, libwebp) in tooling and tests. Output limits follow the messenger animated-sticker
guidance that was found during research: 512 × 512 px and ≤ 500 KB. Those figures come from
third-party API provider documentation, not Meta's own page, which was not reachable; they must be
re-checked before any messenger-specific claim.

**Consequences.** The quality ladder can reduce output size but not frame count. Frame durations are
integers that sum to the loop length. We do not depend on `ImageDecoder` animation support.

## ADR-004 — Manual composition root, no DI framework

**Decision.** `app.AppContainer` is constructed in `StickerJetApplication` and passed to the UI.
Dependency injection frameworks add build time and annotation processing without a benefit at this
size.

## ADR-005 — JSON documents in app-private storage, not Room

**Decision.** Each project is one `project.json` written atomically beside its media. The data volume
is small (tens of projects), the data is document-shaped, and atomic replace is simple to test. Room
would add a schema, KSP and migrations without a current need. Revisit if queries become necessary.

## ADR-006 — Verification in this container versus GitHub Actions

**Context.** The development container cannot resolve Google Maven (`maven.google.com` redirects to
`dl.google.com`, which the egress policy blocks), and the Android SDK cannot be downloaded from there
either. Maven Central and the Gradle plugin portal are reachable, but Maven Central rate-limits this
address.

**Decision.** Pure JVM tests run locally and in CI. Android unit tests, lint, the APK and AAB build,
and instrumented tests run on GitHub-hosted runners, which have the SDK and Google Maven. CI results
are read through the GitHub Actions API. Nothing is reported as verified until a CI run on the exact
commit shows it.

## ADR-007 — No INTERNET permission and no storage permissions

**Decision.** The app requests no runtime permission. Video comes from the Photo Picker (a temporary
per-file grant), and sharing uses a FileProvider grant. CI fails if a merged debug manifest contains
`android.permission.INTERNET`.

**Consequences.** Nothing can be fetched or uploaded. Telemetry, cloud features and updates over the
network are impossible without a future, reviewed change to this decision.

## ADR-008 — Sharing is a file share, not a messenger integration

**Decision.** Exports are shared with `ACTION_SEND` (`image/webp`). No messenger sticker-pack API is
integrated in v0.1, so the app does not claim that any recipient imports the sticker as a pack.
