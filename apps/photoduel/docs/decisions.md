# PhotoDuel decision log (PD-PM)

Entries are append-only. Architecture decisions D-01 to D-12 are in [architecture.md](architecture.md) section 2.

| ID | Date | Decision | Why | Owner |
|---|---|---|---|---|
| PD-PM-01 | 2026-10-10 | The working branch for this build is `claude/lucid-bardeen-2pnmz2`. Nothing is pushed to `main` directly. | The branch is the one provisioned for this session. Merging to `main` needs a reviewed pull request and the root steward's approval for root Gradle changes. | PD-PM |
| PD-PM-02 | 2026-10-10 | Audience is 13 and over. Not directed to children. | UGC with photos. A children's audience would require a separate compliance plan. | PD-PM |
| PD-PM-03 | 2026-10-10 | Minimum 3 members to start a challenge. | With 2 members every ballot is forced and the tally always ties, so the game is not meaningful. | PD-D2 + PD-PM |
| PD-PM-04 | 2026-10-10 | Co-winners on a tie. No hidden tie-break. | Deterministic, explainable, and avoids an arbitrary rule. | PD-D2 |
| PD-PM-05 | 2026-10-10 | Roster is frozen at start. Leaving or removal is blocked during OPEN_FOR_SUBMISSIONS and VOTING. | Prevents a member from dropping out to change the tally or deny a vote. | PD-D2 + PD-D3 |
| PD-PM-06 | 2026-10-10 | Only the first ballot per voter counts, and ballots are immutable. | Matches the single-ballot rule and makes retries safe. | PD-D2 + PD-D3 |
| PD-PM-07 | 2026-10-10 | Photo deletion by the author: allowed while OPEN_FOR_SUBMISSIONS, and after COMPLETED. Not during VOTING. | Stops a player from pulling a photo after seeing the field. | PD-D3 |
| PD-PM-08 | 2026-10-10 | Gradle: a pure JVM module (`:apps:photoduel-core`) with sources that point at the app's own packages, and the Android module (`:apps:photoduel`). `-Pphotoduel.skipAndroid=true` skips Google repositories and the Android module. | Lets domain, data-client and pure engine tests run here, where Google Maven is blocked. Duplicates no source. | PD-PM + PD-D5 |
| PD-PM-09 | 2026-10-10 | Gradle repositories put Maven Central before Google. | Google requests that the egress proxy denies fail the build; Central hits never touch Google. | PD-PM |
| PD-PM-10 | 2026-10-10 | The toolchain (AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2026.09.00, compileSdk 36, minSdk 26) is copied from the TimeFlip branch's reviewed catalogue. Its values are verified only by GitHub Actions. | Consistency with the monorepo; avoid a second toolchain. Root steward must reconcile with StickerJet's older pins before merge. | PD-PM |
| PD-PM-11 | 2026-10-10 | Figma: created the editable design file `u4JSaycaKPivgX6Lr8rgr3` on the team plan. | Requested deliverable. Creation succeeded, so the seat permits creation. | PD-D1 |
| PD-PM-12 | 2026-10-10 | Frozen contracts: architecture section 12 and the domain model and port files were written by PD-PM so the five developers could compile against one definition. | Avoids signature drift across five parallel lanes. | PD-PM |
| PD-PM-13 | 2026-10-10 | Golden result vectors are the shared truth for the tally. A WINNER has exactly one co-winner entry. | One result shape for both outcomes. | PD-PM |
| PD-PM-14 | 2026-10-10 | Invitation text uses the custom scheme. No App Links claimed. A web invitation page is not provided. | No verified domain. | PD-PM + PD-D5 |
| PD-PM-15 | 2026-10-10 | Release builds have no API base URL. The app shows "server not configured". | No production backend exists or is verified. | PD-PM |
