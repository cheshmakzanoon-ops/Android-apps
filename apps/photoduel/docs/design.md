# PhotoDuel design (PD-D1)

**Owner:** PD-D1 (UI). **Code:** `src/main/kotlin/com/agency/photoduel/ui/`. **Status:** written against the pinned toolchain (`gradle/libs.versions.toml`); the Android module has not been compiled or run in this environment (Google Maven is blocked), so nothing here is verified on a device.

## 1. Principles

- **The photo is the content.** The interface is quiet: warm paper neutrals, dark ink and a single cobalt accent. No gradients anywhere.
- **A photo is never covered.** Photos are fitted (`ContentScale.Fit`), never cropped, and nothing is drawn over them. Selection, labels and actions sit in a caption row under the photo; a selected photo gets an accent outline around the whole card.
- **The server decides.** Screens show what the service returned. Countdowns are display-only; when one reaches zero the screen asks the server again. No result, tally or state is computed on the phone.
- **No invented data.** Empty, loading, error, offline and "server not configured" states are real states, never sample content.
- **Calm motion.** Short fades only; nothing slides. With the system "Remove animations" setting, every transition is instant and the results reveal shows the final tally at once.

## 2. Tokens

Source of truth in code: `ui/theme/Color.kt` (`PdTokens.Light`, `PdTokens.Dark`), mirrored in `res/values/colors.xml` (`pd_<token>_light|dark`). `PaletteTokensTest` (JVM unit test of the app module, CI only) fails if the two drift.

| Token | Light | Dark | Use |
|---|---|---|---|
| bg_canvas | #F6F1E7 | #15140F | Screen background |
| bg_surface | #FBF8F2 | #1F1D18 | Cards, sheets |
| bg_sunken | #EDE6D6 | #2A2822 | Photo frames, chips, bars track |
| bg_inverse | #1B1A17 | #F6F1E7 | Offline banner |
| text_primary | #1B1A17 | #F6F1E7 | Body text |
| text_secondary | #5B564B | #C9C1AE | Supporting text |
| accent | #2540C8 | #9DB0FF | Primary actions, selection, winner bars |
| accent_container | #DCE3FF | #1E2A6E | Information notices |
| border / border_strong | #DCD3BF / #8A8270 | #3A372F / #6B6657 | Card outlines / non-winning bars |
| error / error_container | #B3261E / #F9DEDC | #F2B8B5 / #410E0B | Errors, last ten minutes of a countdown |
| success / success_container | #2E6B3A / #D9EBDC | #8FCB9B / #12301A | Material tertiary role |

Type (`ui/theme/Type.kt`, platform default family, sizes in sp so font scaling applies): display 34/42, headline 24/32, title 20/28, body 16/24, label 14/20, caption 12/16, countdown 28/36 with tabular figures.
Spacing (`ui/theme/Dimens.kt`): 4, 8, 12, 16, 24, 32, 48 dp; gutter 16 dp; touch target 48 dp; content max width 640 dp. Radius: 8, 12, 20, 28 dp.
Motion (`ui/theme/Motion.kt`): 100 / 150 / 250 / 400 ms, results bars 900 ms. `LocalReduceMotion` follows the system animator scale through Compose's `MotionDurationScale` (no Context access).

## 3. Shared components (`ui/common`)

| Component | Notes |
|---|---|
| `PdButton` | Primary, secondary, tertiary, destructive. States: enabled, disabled, loading (spinner, taps ignored, TalkBack state "Working"). Min height 48 dp. |
| `PhotoFrame`, `PhotoCard`, `PhotoViewerDialog` | Fitted photo on a sunken frame with "Loading photo…" / "Photo unavailable"; caption row; radio semantics when selectable; full-screen viewer on a dark backdrop. |
| `CountdownChip` | `HH:MM:SS` (or `Nd HH:MM:SS`); error colours in the last 10 minutes; TalkBack reads "Voting closes in 12 minutes", never seconds. |
| `LoadingState`, `ErrorState`, `EmptyState`, `InlineError`, `InlineNotice`, `StaleNotice`, `OfflineBanner` | Every failure maps an `ErrorCode` to its own sentence (`errorMessage`). Server messages are not shown. Lost connection shows "Reconnecting…" while the state layer retries. |
| `PdScreen`, `ScrollColumn`, `SectionHeading`, `ConfirmDialog` | Titles and section titles are TalkBack headings. Destructive confirms name the action. |

## 4. Screens and states

Navigation is Navigation Compose 2.9.0 with type-safe routes (`ui/navigation/Routes.kt`, a sealed interface). The invitation route also declares the deep link `photoduel://invite/{token}`; in the app, tokens arrive through `UiDependencies.pendingInviteTokens` and wait until the user is signed in. Onboarding, sign-in and "server not configured" are chosen by the root from the session phase, so a signed-out user cannot reach the signed-in graph.

| Screen | States shown |
|---|---|
| Onboarding (3 pages) | Skip, Back, Next, Get started; completion reported through `onOnboardingFinished`. |
| Sign-in / restore | Name validation (empty, too long, character), restore code format, wrong code, new code shown once, "code not issued". |
| Home | Loading, error, empty, rooms with state, join-with-link dialog (invalid link message). |
| Create room | Validation, quota error, success opens the room. |
| Room | Owner and member views; invite (lifetime, create, share via `invitationSharing`, revoke, shown once); start (disabled under 3 members, with reason), cancel, remove, report, block, leave, delete. |
| Create challenge / rematch | Prompt list from the catalogue; submission 1/6/24/48/72 h; voting 1/6/24 h; rematch does not preselect the last prompt. |
| Invitation | Preview, join, room full, one message for every unusable link. |
| Challenge | Waiting, submit, submitted, vote, vote cast, results, cancelled, archived, watching; countdown. |
| Submission | Choose, preview, preparing (stage), uploading (progress), cancel, failed + retry, submitted, replace / keep current, withdraw (confirmed), closed. |
| Voting | Intro, ballot (tap selects, Confirm names the author, haptics on select / cast / refusal), own photo not selectable, done, unavailable. |
| Results | Animated bars then winner; tie shows every co-winner; insufficient photos / votes say "No winner"; reduced motion shows the final state at once; delete own photo; rematch (owner). |
| History, Profile, Settings, Guidelines, Blocked people | Paging; rename, recovery code once, sign out, delete with typed `DELETE`; reminders switch shown off and disabled with the reason (no capability in `UiDependencies`); in-app guideline pages (no web links exist). |

## 5. Accessibility

TalkBack: every icon button has a label; photo cards read "Photo by Ben" (own photo: "Your photo. You can't vote for it."); tally rows read "Ben: 2 votes, winner"; countdowns read rounded durations; errors and progress are polite live regions. All text is in `res/values/strings.xml`. Not verified with TalkBack on a device.

## 6. Figma

- **File:** https://www.figma.com/design/u4JSaycaKPivgX6Lr8rgr3 (created earlier with `create_new_file`, see architecture.md section 3 and decision PD-PM-11).
- **This session:** every Figma MCP call (`get_metadata`, `use_figma`) was refused with "You've reached the Figma MCP tool call limit on the Starter plan". Nothing was read from or written to the file.
- **Frames confirmed to exist:** none. The comment in `res/values/colors.xml` names variable collections "Color Light" and "Color Dark"; they were not re-checked in this session and are not claimed here.
- **Missing (requested, not created):** design variables (colour, spacing, radius, type), and the frames Welcome, Home, Room, Challenge, Voting, Results, Profile, Settings, Error state, Empty state.
- **Next step:** with a plan that allows MCP calls, create the variables from section 2 and the ten frames above, then record the node ids here.
