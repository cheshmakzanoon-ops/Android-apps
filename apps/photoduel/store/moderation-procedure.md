# PhotoDuel moderation procedure — DRAFT (operator to approve)

## Roles
- **Operator / incident owner:** [name — PENDING]. Accountable for response times and for the moderation log.
- **Moderator:** a named person with database and CLI access. [PENDING]
- **Escalation contact for legal or safety emergencies:** [PENDING]

No account on the Android client, the repository or the API has moderation powers. Moderation runs only from the operator's machine with database access, through `npm run mod`.

## Intake
1. Users report a photo or a user inside the app (Report sheet). The report stores a reason, optional details up to 280 characters, the reporter, and the target.
2. Three distinct reporters on one photo automatically hide it from ballots and galleries (`HIDDEN`), pending review.
3. Review queue: `npm run mod -- list-reports`.

## Triage and action
| Reason | Typical action | Notes |
|---|---|---|
| Nudity or sexual content, violence, self-harm content | Hide the photo immediately. Consider suspending the account. | Preserve the audit row. Escalate according to law. |
| Harassment, hate | Hide the photo; suspend if repeated | Look at the room's history before deciding |
| Privacy (identifiable third party or document) | Hide the photo | The author should be told why |
| Spam or not a photo of the prompt | Dismiss or hide | Quality matters less than safety |
| Other | Review the details, then decide | — |

Commands (each writes an audit row with the operator name):
- `npm run mod -- hide-submission <id> --reason "<text>"`
- `npm run mod -- restore-submission <id>`
- `npm run mod -- dismiss-report <id>`
- `npm run mod -- suspend-user <id> --reason "<text>"` (sign-in is refused on every request)
- `npm run mod -- restore-user <id>`
- `npm run mod -- purge-room <id>`

## Timing
- Target: review each report within 24 hours. Unreviewed reports older than 24 hours trigger a daily digest to the operator. [digest mechanism PENDING — not implemented in this build]

## Appeals
A user can contact the moderation address. A moderator who did not take the original decision reviews the appeal and records the outcome in the audit log.

## Records
Audit rows are kept for 180 days. Reports are kept for 180 days after resolution.

## Gaps in this build (must be closed before public launch)
- No automatic notification to users when content is removed.
- No digest or alerting for report backlog.
- No staffed rota or named person.
