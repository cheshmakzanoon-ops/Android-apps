# PhotoDuel data deletion and retention — DRAFT

| Event | Effect | Timing |
|---|---|---|
| Photo of a finished challenge | Media files purged; the challenge becomes ARCHIVED; results and tallies kept | 30 days after COMPLETED |
| Photo of a cancelled challenge | Media files purged | Within 24 hours of cancellation |
| Photo uploaded but never linked to a submission | Orphan file purged | 1 hour |
| Room deleted by owner | Room hidden at once; photos purged; rows removed | Photos within 24 hours; rows after 7 days |
| Account deleted in app | Session revoked; photos purged; display name cleared; ballots kept without voter identity | Immediately, except the purge job may run up to 24 hours later |
| Expired session | Purged | Daily |
| Rate-limit counters | Purged | After 48 hours |
| Audit log | Kept for operator accountability | 180 days |
| Reports | Kept for safety review | 180 days after resolution |

**Exception:** tallies and result rows are kept so that a finished result can still be reproduced after the photos are gone. They do not contain voter identities.

**Web deletion request:** required by Google Play for apps that allow sign-up. The page is **not yet available** because no domain exists. This is a release blocker, listed in release-checklist.md.

**Operational note:** the purge job runs in the backend worker every 30 seconds. Its success is not monitored in this build. A missing monitor is a known gap.
