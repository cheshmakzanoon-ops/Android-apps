# PhotoDuel delivery team

No real GitHub usernames have been assigned to these seats. Populate them only after collaborator onboarding. Seats are role labels, not confirmed staff.

| Role seat | Responsibility | Source paths | GitHub handle |
|---|---|---|---|
| PD-D1 | Android UX, Compose screens, Figma design system | `ui/`, `res/values/`, `androidTest/ui/` | Unassigned |
| PD-D2 | Domain rules, models, ports, in-memory fake | `domain/`, `test/.../domain/` | Unassigned |
| PD-D3 | Backend, database, security, remote client | `backend/`, `data/remote/` | Unassigned |
| PD-D4 | Photo pipeline, metadata stripping, prompt catalogue | `engine/`, `assets/source/`, `benchmarks/` | Unassigned |
| PD-D5 | App shell, platform integrations, CI, QA and journeys | `app/`, `platform/`, manifest, `.github/workflows/photoduel.yml`, `androidTest/journey/`, `docs/qa-test-plan.md` | Unassigned |
| PD-PM | Scope, decisions, contracts, release gates, store drafts | `docs/product.md`, `docs/decisions.md`, `docs/release-checklist.md`, `store/` | Unassigned |

**Working model on this branch:** the five developers ran in parallel against frozen contracts (`docs/architecture.md` section 12, `domain/model`, `domain/port`, `ui/UiDependencies.kt`). The PM integrated their output, ran the verification commands and recorded the results. Each developer's own test results are reported in the release-checklist evidence, not claimed here.

**Not done in this build:** physical-device testing, Play Console work, production hosting, moderation staffing and external privacy review. These seats must be staffed by real people before public release.
