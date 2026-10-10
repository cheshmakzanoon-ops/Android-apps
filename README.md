# Android Apps Agency — Ten-Product Monorepo

**Agency master plan:** [project-outline-android-apps.md](project-outline-android-apps.md) (11,000+ words; ten unique squads, five concurrent developers and one dedicated PM per app).

**Current state:** G0 planning/scaffolding; the Kotlin/Gradle app modules have **not yet been implemented**. No APK, AAB, Play publication or test success is claimed. [Agency status tracker](docs/agency/STATUS.md).

## Product squads

| App | Directory | PM role | Key user promise |
|---|---|---|---|
| StickerJet | [apps/stickerjet](apps/stickerjet) | SJ-PM | Animated sticker exports |
| TimeFlip | [apps/timeflip](apps/timeflip) | TF-PM | Before/after video transformations |
| PhotoDuel | [apps/photoduel](apps/photoduel) | PD-PM | Private photo challenges |
| RetroWorld | [apps/retroworld](apps/retroworld) | RW-PM | Retro-styled images and clips |
| FluidOS | [apps/fluidos](apps/fluidos) | FO-PM | Interactive live wallpapers |
| SnapBrain | [apps/snapbrain](apps/snapbrain) | SB-PM | Local screenshot OCR search |
| BeatBloom | [apps/beatbloom](apps/beatbloom) | BB-PM | Hummed/tapped music arrangements |
| BillFlash | [apps/billflash](apps/billflash) | BF-PM | Accurate OCR-assisted bill splitting |
| PocketCompanion | [apps/pocketcompanion](apps/pocketcompanion) | PC-PM | Creature-based focus companion |
| WalkWorld | [apps/walkworld](apps/walkworld) | WW-PM | Route-to-miniature postcard |

Each app has a [README.md](apps/timeflip/README.md)-style product card and `TEAM.md` role ownership sheet. Replace role placeholders with actual permitted GitHub collaborators during onboarding. Review the agency's [release gate template](docs/agency/RELEASE_CHECKLIST_TEMPLATE.md) and [PR template](.github/PULL_REQUEST_TEMPLATE.md).

## First implementation step

Coordinate a G1 pull request that adds a verified Android Gradle root, ten compilable application modules, D2 contracts/mocks and path-filtered CI. Follow the master outline for branching, privacy, testing, physical-device proof and PM go/no-go decisions. Do not treat the skeleton as finished app software.

## Repository

https://github.com/cheshmakzanoon-ops/Android-apps
