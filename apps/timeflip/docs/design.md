# TimeFlip UI design record (TF-D1)

Status: Android UI written and partly verified (see "Verification"). Figma design work is **blocked**.

## Figma

- File: https://www.figma.com/design/01ffCnUIwnQsd4WjgjJHka (fileKey `01ffCnUIwnQsd4WjgjJHka`), name "TimeFlip — Android Design System & Product UI".
- State found on read: one page, "Page 1" (`0:1`). No Design System page, no frames, no components, no styles or variables were created.
- Blocker (exact error returned by the first write attempt, `use_figma`, Design System page creation):

  > You've reached the Figma MCP tool call limit on the Starter plan. Upgrade your plan for more tool calls: https://www.figma.com/files/team/1690775658102224588/all-projects?upgrade=mcp_rate_limit_paywall
  > [Resource link: rate-limits-access.md] file://figma/docs/rate-limits-access.md

- Context: `whoami` reports the plan `Cheshmak Zanoon's team` (key `team::1690775658102224588`), tier `starter`, seat `View`. Reads worked; the write was rate-limited. Work stopped per the brief; no retries were made.
- Still to do once the limit lifts: (1) Design System page with colour styles, text styles, and spacing/radius variables matching `ui/theme`; (2) frames at 390 x 844 for Welcome, Library, New pair, Alignment review, Manual alignment, Editor, Export progress, Export success, Settings; (3) components for the primary button, transition chip and comparison slider. Frame content must follow the implemented layouts described below.

## What is designed in code (tokens)

Source of truth: `ui/theme/Color.kt`, `ui/theme/Type.kt`, `ui/theme/Dimens.kt`, `ui/TimeFlipTheme.kt`.

- Colour (`TimeFlipColor`, dark only): Background `#111214` (matches launcher window), Surface `#1A1B20`, SurfaceVariant `#25272E`, SurfaceRaised `#2E3139`, Outline `#6E737E`, OutlineVariant `#3A3E47`, OnBackground `#F2F0EA` (launcher off-white), OnSurfaceVariant `#B9B6AE`, Accent `#E0A458` (launcher accent), OnAccent `#1E1408`, AccentContainer `#3D2D14`, OnAccentContainer `#F6DDB5`, Error `#FF8A80`, OnError `#3A0B07`, ErrorContainer `#5C1A14`, OnErrorContainer `#FFDAD5`, Positive `#8FCB9B`.
- Type (`TimeFlipType`, system default family only): Title 24/30 SemiBold, Section 16/22 Medium, Body 16/24, BodySmall 14/20, Label 14/20 Medium, Caption 12/16, Readout 20/26 Medium with tabular figures.
- Spacing (`TimeFlipSpacing`): 4, 8, 12, 16, 24, 32 dp. Radius (`TimeFlipRadius`): 4 and 8 dp. Minimum touch target 48 dp (`TimeFlipSize`).
- Components implemented (Compose): `PrimaryButton`, `SecondaryButton`, `TextActionButton`, `SelectableChip` (transition chips and presets), `ComparisonDivider`, `LabeledSlider`, `BottomActionBar`, `FailurePanel`, `PhotoThumbnail`.

## What is implemented (screens)

| Screen | Route | ViewModel |
|---|---|---|
| Welcome | `welcome` | `WelcomeViewModel` |
| Library | `library` | `LibraryViewModel` (rename, duplicate, delete, empty/loading/failed states) |
| New pair | `newPair?projectId=` | `NewPairViewModel` |
| Alignment review | `alignReview/{projectId}` | `AlignReviewViewModel` |
| Manual alignment | `manualAlign/{projectId}?wizard=` | `ManualAlignViewModel` (+ `AlignmentFit`) |
| Editor | `editor/{projectId}` | `EditorViewModel` |
| Export | `export/{projectId}` | `ExportViewModel` |
| Settings | `settings` | `SettingsViewModel` |
| Start routing | (root) | `AppStartViewModel` |

## Differences from the brief (and why)

- **New transformation advances automatically** once both photos are imported (the BEFORE and AFTER photos are chosen, then alignment starts). The brief's primary flow and the instrumented journey both expect this. Continue is kept only for editing an existing project, where swap and replace need an explicit commit.
- **Alignment actions**: "Open editor" applies the automatic result only when it is ACCEPTED (confidence at or above 60%). For NEEDS_MANUAL or FAILED estimates, the primary action is "Adjust manually", and "Open editor" opens the editor without applying the estimate. The brief's "use anyway" action was dropped so a low-confidence estimate is never presented as the placement.
- **Reset** in manual alignment returns to the cover-fit placement (identity transform); it is undoable.
- **Fit** zooms in only as far as needed to remove empty edges; it keeps rotation and position.
- **Preview frame** is the output preset scaled to 720 px on its longest edge.
- **Playback** is user-started (play, replay, scrub). The screens have no decorative motion, and `reducedMotionEnabled` (platform) is not called anywhere in `ui/`, so there is no reduced-motion branch to test. Add one if a motion effect is introduced.
- **Export starts once per screen entry**. After process death the screen shows "Start export" (the export is not resumed; the MVP has no background export).
- **Theme** is dark only. There is no light theme.
- **Version string** is not shown in Settings.

## Verification

- Verified here: the domain code and every ViewModel compile with Kotlin 2.2.21 against real kotlinx-coroutines 1.10.2, using local stand-ins for the three androidx.lifecycle types (`ViewModel`, `viewModelScope`, `SavedStateHandle`). The 85 JVM tests pass: 46 existing domain tests and 39 UI tests (NewPair creation and edit paths, ManualAlign undo/redo and gesture clamping, Export progress, cancel and failure, Library empty state and CRUD, AlignReview accept, low-confidence and missing-photo paths, Editor restore and playback, AlignmentFit).
- Not verified: all Compose, navigation and platform code, the two Robolectric Compose tests (`ui/compose/LibraryScreenTest.kt`, `ui/compose/EditorControlsTest.kt`), and the previews. The Android SDK and Compose artifacts cannot be downloaded in this sandbox. CI must compile them.
- Screenshots from the real app have not been taken. They come later from CI.
