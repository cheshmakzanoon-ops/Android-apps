# StickerJet design system and screen specification

Owner: SJ-D1 (Android UX and UI). Status: code complete for the ui lane; Figma content **not built** (see
section 1). The Compose sources in `src/main/kotlin/com/agency/stickerjet/ui/` are the source of truth. Where
this document and the code disagree, the code wins and this file must be updated.

## 1. Figma status

- Design file created: https://www.figma.com/design/bIyH2Dx1UrHanmMjXTcdqj (`StickerJet — Android Product Design System`,
  plan `team::1690775658102224588`, editor `design`). The file exists and is empty.
- Blocker: after creation, every Figma write was refused with the exact message
  `You've reached the Figma MCP tool call limit on the Starter plan. Upgrade your plan for more tool calls:
  https://www.figma.com/files/team/1690775658102224588/all-projects?upgrade=mcp_rate_limit_paywall`
  (resource `file://figma/docs/rate-limits-access.md`). The reported seat is `View`.
- Therefore **no Figma variables, styles, components or screen frames exist yet.** The tokens and screen
  inventory below are the specification to build them from. Nothing in this document claims a Figma frame.
- To finish: raise the plan limit or restore write access, then build the collections in section 3 and the
  frames in section 7, and record their IDs here.

## 2. Principles

- Calm, sticker-like surfaces: Paper and Ink dominate, Jet is the action colour, Sea and Sun are secondary
  fills, Lilac is a sparing accent (at most one badge per screen).
- Ink is the label colour on every filled brand colour. White on Jet is 2.85:1 and fails text contrast.
- Jet is never used as text or a thin line on Paper (2.68:1). Selected states use Ink plus a fill.
- Every text pair meets 4.5:1; every non-text boundary meets 3:1 (WCAG 1.4.3 and 1.4.11).
- Nothing is conveyed by colour alone: status has an icon and a sentence.
- Motion is short and purposeful, and collapses to a snap when the system has removed animations.
- No gradients, no shadows, no Material default chrome (rounded corners and pill buttons throughout).

## 3. Colour tokens

Source: `ui/theme/Color.kt` (`StickerPalette`, `StickerLightScheme`, `StickerDarkScheme`, `StickerLightExtra`,
`StickerDarkExtra`). Brand values are fixed by the brief. Derived tones are marked (d).

| Token (Material role) | Light | Dark | Notes |
|---|---|---|---|
| Ink (brand) | `#1C1B2E` | — | text, outlines, labels on fills |
| Paper (brand) | `#FFF7EC` | — | light background and surface |
| Jet (brand) | `#FF6A3D` | `#FF6A3D` | primary fill, label Ink |
| Sea (brand) | `#2BB3A3` | `#2BB3A3` | secondary fill, kept-subject tint |
| Sun (brand) | `#FFC857` | `#FFC857` | tertiary fill, caption swatch |
| Lilac (brand) | `#8B7CF6` | `#8B7CF6` | accent only (`StickerExtraColors.accent`) |
| background | Paper | Ink | |
| onBackground | Ink | Paper | |
| surface | Paper | `#27263B` (d) | cards and sheets |
| onSurface | Ink | Paper | |
| surfaceVariant | `#F2E6D4` (d) | `#33324A` (d) | chips, placeholders |
| onSurfaceVariant | `#4B4860` (d) | `#C5C1D6` (d) | secondary text |
| primaryContainer / on | `#FFE3D6` (d) / Ink | `#6B2B1C` (d) / `#FFE3D6` | |
| secondaryContainer / on | `#D6F3EE` (d) / Ink | `#15524B` (d) / `#D6F3EE` | |
| tertiaryContainer / on | `#FFEBB8` (d) / Ink | `#5C4300` (d) / `#FFEBB8` | |
| error | `#B42318` (d) | `#FFB4A8` (d) | errors, source-missing text |
| onError | `#FFFFFF` | `#5C0F09` (d) | |
| errorContainer / on | `#F9DAD5` (d) / Ink | `#5C0F09` (d) / `#FFB4A8` | banners |
| outline | `#6F6A85` (d) | `#8F8AA6` (d) | borders, non-text (3:1) |
| outlineVariant | `#D8CDBD` (d) | `#45435E` (d) | dividers |
| accent / onAccent (extra) | Lilac / Ink | Lilac / Ink | one badge per screen |
| keptTint / removedTint (extra) | Sea / Jet | Sea / Jet | brush overlay, drawn at 35% alpha |
| checkerA / checkerB (extra) | `#FFFFFF` / `#E4DACB` (d) | `#33324A` / `#27263B` (d) | transparency pattern |

Derived tones (d) are not in the brief. The error red and the container tints are proposals for brand review.

### Measured contrast (WCAG 2.x, computed with the formula in `WcagContrast`)

| Pair | Ratio | Result |
|---|---|---|
| Ink on Paper | 15.87 | pass |
| Ink on Jet (onPrimary) | 5.93 | pass |
| Ink on Sea | 6.49 | pass |
| Ink on Sun | 10.96 | pass |
| Ink on Lilac (onAccent) | 5.07 | pass |
| Ink-muted `#4B4860` on Paper | 8.25 | pass |
| Ink-muted on surfaceVariant | 7.12 | pass |
| White on error `#B42318` | 6.57 | pass |
| error `#B42318` on Paper | 6.19 | pass |
| Ink on jet/sea/sun/error containers | 13.82 / 14.38 / 14.31 / 12.86 | pass |
| Paper on dark surface `#27263B` | 13.86 | pass |
| dark onSurfaceVariant on dark surfaceVariant | 7.06 | pass |
| dark error `#FFB4A8` on `#5C0F09` | 8.08 | pass |
| outline light on Paper (non-text) | 4.85 | pass (3:1) |
| outline dark on Ink (non-text) | 5.10 | pass (3:1) |
| White on Jet | 2.85 | **rejected** for text |
| Jet on Paper | 2.68 | **never used as text** |
| White on Lilac | 3.33 | **rejected** for text |

`ThemeContrastTest` asserts the text pairs at 4.5:1 and the outline pairs at 3:1 on every build.

## 4. Typography

Source: `ui/theme/Type.kt`. Font: **Nunito** (SIL Open Font License 1.1, The Nunito Project Authors), bundled as
static instances in `res/font/` (`nunito_regular`, `nunito_semibold`, `nunito_bold`, `nunito_extrabold`,
~130 KB each), generated from the variable font so no runtime weight selection is needed. All sizes are `sp`.

| Style | Size / line (sp) | Weight | Used for |
|---|---|---|---|
| displaySmall | 36 / 42 | ExtraBold | reserved (welcome mark text) |
| headlineLarge | 30 / 36 | ExtraBold | welcome title |
| headlineMedium | 26 / 32 | Bold | reserved |
| headlineSmall | 22 / 28 | Bold | top-bar titles, dialog titles |
| titleLarge | 20 / 26 | Bold | section titles, status titles |
| titleMedium | 17 / 23 | Bold | toggles, group titles |
| titleSmall | 16 / 22 | SemiBold | tile titles |
| bodyLarge | 17 / 24 | Regular | body copy, status bodies |
| bodyMedium | 15 / 22 | Regular | hints, meta |
| bodySmall | 15 / 21 | Regular | captions under controls |
| labelLarge | 16 / 20 | Bold | buttons, slider labels |
| labelMedium | 14 / 18 | SemiBold | badges, field labels |
| labelSmall | 12 / 16 | SemiBold | reserved (smallest allowed) |

Minimum size is 12 sp (labelSmall, reserved); body text is never below 15 sp.

Licence: the OFL text is `Nunito` `OFL.txt` (copyright 2014 The Nunito Project Authors). `apps/stickerjet/assets/licenses/`
is outside the SJ-D1 paths, so the copy must be placed by the PM (see the report handoff).

## 5. Spacing, shape, touch targets

Source: `ui/theme/Spacing.kt`, `ui/theme/Shape.kt`.

- Spacing (dp): xxs 4, xs 8, sm 12, md 16, lg 24, xl 32, xxl 48. Screen gutter 16.
- Corner radii (dp): extraSmall 8, small 12, medium 16 (cards), large 24 (dialogs, panels), extraLarge 32.
  Pill (50%) for buttons and segments. Sticker tiles 24.
- Touch targets: 48 dp minimum for every button, tab, swatch, icon button, slider track and tile action.
  `StickerChoiceRow` segments use `defaultMinSize(minHeight = 48dp)`. Swatches are 48 dp squares with a 32 dp visual.

## 6. Motion and reduced motion

Source: `ui/theme/Motion.kt`.

- Standard transition: 240 ms, FastOutSlowIn. Used by the shared motion spec.
- Reduced motion: `rememberReducedMotion()` reads `Settings.Global.ANIMATOR_DURATION_SCALE`; when it is 0
  (Remove animations) every spec becomes a snap, and previews start paused.
- Loops (trim loop, preview, export) follow the frame durations from `ExportPlan` (83/84 ms at 12 fps). Each
  loop has a Play/Pause control, because an auto-playing loop must be pausable.
- Progress: linear determinate bar for export and circular indeterminate for detection. Live regions use
  `LiveRegionMode.Polite` so status changes are spoken without stealing focus.

## 7. Components (Compose)

| Component | Source | States |
|---|---|---|
| `StickerPrimaryButton` | `components/StickerButtons.kt` | enabled, disabled |
| `StickerSecondaryButton` | same | enabled, disabled |
| `StickerTextButton` | same | enabled, disabled |
| `StickerTopBar` | `components/StickerTopBar.kt` | with/without back, with actions |
| `StickerTabRow` | `components/StickerTabRow.kt` | selected per tab (Ink indicator) |
| `StickerChoiceRow` | `components/ChoiceRow.kt` | selected segment, disabled group |
| `StatusPanel` | `components/StatusPanel.kt` | EMPTY, ERROR, PERMISSION, UNSUPPORTED, WARNING |
| `LoadingPanel` | same | spinner, determinate with cancel |
| `ConfirmDialog` / `TypedConfirmDialog` | `components/ConfirmDialogs.kt` | destructive, typed DELETE |
| `LabeledSlider` | `components/LabeledSlider.kt` | value spoken as state description |
| `StickerStage` | `editor/StickerStage.kt` | still, caption top/bottom, light/dark background |
| `LoopPlayer` + `PlayPauseButton` | `editor/LoopPlayer.kt` | playing, paused |
| `BrushSurface` | `editor/BrushSurface.kt` | enabled, disabled, drag in progress |
| `ProjectTile` / `RecentExportCard` | `library/LibraryTiles.kt` | normal, source missing, file missing |

## 8. Screens and states

Each screen maps to Compose code. Phone width 360 dp, height 800 dp minimum. All screens respect status
and navigation bar insets (targetSdk 36 is edge to edge).

| # | Screen | Compose | States in code |
|---|---|---|---|
| 1 | Welcome | `welcome/WelcomeScreen.kt` | first run only; privacy card; one "Get started" action |
| 2 | Library (populated) | `library/LibraryScreen.kt` | recent-exports row, drafts grid, Import bottom bar |
| 3 | Library (empty) | same | "No stickers yet" panel with Import |
| 4 | Library (source missing) | `library/LibraryTiles.kt` | tile shows "Original video missing" in error colour |
| 5 | Library (delete confirm) | same | destructive `ConfirmDialog` |
| 6 | History (recent exports) | same, `RecentExportCard` row | Open and Share; "File missing" when the file is gone |
| 7 | Import (idle) | `importing/ImportScreen.kt` | limits, privacy line, "Choose a video" |
| 8 | Import (processing) | same | `LoadingPanel` with cancel |
| 9 | Import (rejected) | same | reason text for each rejection, "Choose another video" |
| 10 | Import (permission denied) | same | access-revoked panel, retry and choose another |
| 11 | Import (failed) | same | storage or IO failure, retry and choose another |
| 12 | Import (unsupported) | same | picker unavailable panel |
| 13 | Editor shell | `editor/EditorScreen.kt` | title, Undo/Redo, tab row, banners for missing source and save failure |
| 14 | Trim | `editor/TrimTab.kt` | length label, limits hint, loop, start and end sliders |
| 15 | Subject (person found) | `editor/SubjectTab.kt` | switch on, matte preview, brush modes and sizes, Reset fixes |
| 16 | Subject (no person) | same | "No person was found ... original background is kept" |
| 17 | Subject (detection failed) | same | failure text and "Find the person again" |
| 18 | Style | `editor/StyleTab.kt` | stage (drag, zoom), light/dark, size, rotate, reset, caption |
| 19 | Preview (rendering) | `editor/PreviewTab.kt` | loading panel |
| 20 | Preview (loop) | same | loop, Play/Pause, frame and length summary, subject count |
| 21 | Export (idle) | same | hint and "Export sticker" |
| 22 | Export (progress) | same | determinate bar, percent, Cancel export |
| 23 | Export (success / share) | same | "Sticker exported", "Saved in library" badge, Share sticker |
| 24 | Export (failure) | same | reason for each failure, draft kept, Try again, Keep editing |
| 25 | Export (source missing) | same | export unavailable panel |
| 26 | Settings | `settings/SettingsScreen.kt` | privacy, cleanup status, delete typed confirm, licences, version |

Notes for the Figma build when it happens: the History frame (6) is the recent-exports section of the Library,
not a separate route; the code has no History route. Frames 19 to 25 are states of the Preview tab.

## 9. Copy

All user-visible text lives in `res/values/strings.xml` (173 keys). Plain sentences, no emoji, no jargon.
Failures always say what happened and what to do, and say whether the draft was kept.

## 10. Accessibility checks

- Icon-only controls have `contentDescription`: back, settings, delete per tile, thumbnails, swatches (by colour name).
- `AccessibilityLabelsTest` (instrumented) asserts the 48 dp minimum on the settings icon and the Welcome action.
- `ThemeContrastTest` (JVM) asserts the contrast table on every build.
- Headings use `semantics { heading() }` (top bars, section titles, Welcome title).
- Sliders expose their label as `contentDescription` and the value as `stateDescription`.
- Segmented choices use radio-group semantics; the Subject and Preview switches use `toggleable` with `Role.Switch`.

## 11. Known gaps and decisions for the owners

- Caption legibility: `StickerStage` is an approximation. The exported caption comes from `CaptionRenderer`
  (SJ-D4). Captions in Paper or Sun over light photos can be illegible. Request: SJ-D4 draws a plate or an outline.
- The Style stage applies placement with Compose transforms, not `StickerComposer`. The rendered truth is the Preview.
- Reset of manual fixes uses `copy(maskStrokes = emptyList())` in `EditorSubject.kt`, because the domain has no
  clear helper. Request: SJ-D2 adds a validated `withoutStrokes` in `StickerEdits.kt`.
- The brief has no rename screen, so `StickerProjects.normalizeTitle` / `withTitle` are not used by the UI.
- Derived colour tones (section 3) need brand approval.
- ML Kit licence wording in Settings needs legal review before Play submission.
