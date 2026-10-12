# PhotoDuel backend

Authoritative API for PhotoDuel: guest identity, rooms, invitations, challenges, submissions,
ballots, results, media authorization and safety. Node.js 22, TypeScript, Fastify 5 and
PostgreSQL 16. The contracts are [`../docs/architecture.md`](../docs/architecture.md) and
[`../docs/api-contract.md`](../docs/api-contract.md); the shared tally vectors are
[`../contracts/vectors/results.v1.json`](../contracts/vectors/results.v1.json).

## Layout

| Path | What |
|---|---|
| `src/server.ts` | HTTP entry point (`npm start`) |
| `src/app.ts` | `buildApp(deps)`: routes, auth hook, error mapping. Used by the server and by every test |
| `src/routes/` | identity, rooms and invitations, challenges, submissions and ballots and media, safety, meta |
| `src/lib/` | tally, image normalization, object store, rate limits, audit, crypto, clock, db |
| `src/lifecycle.ts` | Server-time state machine, applied lazily under row locks and by the worker |
| `src/worker.ts`, `src/workerCli.ts` | Deadlines, purges and retention (`npm run worker`) |
| `src/cli/mod.ts` | Moderation CLI (`npm run mod`). The only moderation surface |
| `migrations/` | Forward-only SQL, checksummed (`npm run migrate`) |
| `test/unit/` | Library, migration and golden-vector tests |
| `test/security/` | Authorization, abuse and privacy tests against the real database |
| `test/e2e/` | Multi-user lifecycle over real HTTP |
| `scripts/local-postgres.sh` | Local PostgreSQL 16 cluster for development (non-root only) |

## Run

```sh
npm ci
npm run build                     # tsc -> dist/
cp .env.example .env              # then edit DATABASE_URL and the secrets
node --env-file=.env dist/src/migrateCli.js   # same as: npm run migrate (with the variables exported)
node --env-file=.env dist/src/server.js       # same as: npm start
```

`npm start`, `npm run migrate`, `npm run worker` and `npm run mod` read `process.env` only. Either
export the variables (`set -a; . ./.env; set +a`) or call `node --env-file=.env` as above.

- Health check: `GET /v1/health` (no authentication).
- The worker runs inside the API process by default (`WORKER_IN_PROCESS=true`). To run it on its
  own: `npm run worker` (loop) or `node dist/src/workerCli.js --once` (one pass).
- Moderation: `npm run mod -- list-reports`, `show-report <id>`, `hide-submission <id> --reason "..."`,
  `restore-submission <id>`, `dismiss-report <id>`, `suspend-user <id> --reason "..."`,
  `restore-user <id>`, `purge-room <id>`. Every subcommand writes an `audit_log` row naming the
  operator (`--operator <name>` or `MOD_OPERATOR`).

## Environment variables

Every variable, with its default, is listed in [`.env.example`](.env.example). The configuration is
validated at startup and the process stops with a message naming each invalid variable (values are
never printed).

| Variable | Default | Notes |
|---|---|---|
| `NODE_ENV` | `development` | `production` requires `IP_HASH_SALT` and `RECOVERY_CODE_PEPPER` (16+ characters) |
| `HOST`, `PORT` | `127.0.0.1`, `8787` | |
| `DATABASE_URL` | none | Required by the server, migrations, worker and moderation CLI |
| `DATABASE_SCHEMA` | `public` | Every connection uses this `search_path` |
| `PG_POOL_MAX` | `10` | |
| `MEDIA_ROOT` | `var/media` | Private local object store. Production target is a private S3-compatible bucket, not provisioned here |
| `PROMPTS_PATH` | `../assets/source/prompts/prompts.v1.json` | Read in place at startup; the server stops if it is missing or invalid |
| `LOG_LEVEL` | `info` | Structured JSON; no tokens, bodies, photo bytes or EXIF data |
| `TRUST_PROXY` | `false` | Set `true` only behind a proxy you control |
| `IP_HASH_SALT`, `RECOVERY_CODE_PEPPER` | development-only value | Secrets. Set real values outside development |
| `RECOVERY_SCRYPT_N` | `16384` | scrypt cost for recovery codes |
| `WORKER_IN_PROCESS`, `WORKER_INTERVAL_MS` | `true`, `30000` | |
| `SESSION_TTL_DAYS` | `90` | Sliding expiry |
| Limits | see `.env.example` | Guests per IP per hour, rooms per day, invitations per room, capacity, uploads, body sizes, ballots per minute, reports per day, auto-hide threshold, pixel and short-edge limits |
| `MOD_OPERATOR` | OS user name | Moderation CLI only |
| `TEST_DATABASE_URL` | none | Tests only. `DATABASE_URL` is ignored by the tests |

## Tests

All suites run against a real PostgreSQL 16 database given by `TEST_DATABASE_URL`. Each test file
creates its own schema (`pd_test_<random>`), migrates it, and drops it at the end, with its own
media directory under the OS temp folder and its own manual clock. Nothing else in the database
is touched, but use a disposable database anyway.

```sh
npm run build
TEST_DATABASE_URL=postgres://USER@127.0.0.1:PORT/TEST_DATABASE npm test          # unit + security
TEST_DATABASE_URL=postgres://USER@127.0.0.1:PORT/TEST_DATABASE npm run test:e2e  # end to end
```

- `npm test` runs `dist/test/unit/**` and `dist/test/security/**`, four files at a time.
- `npm run test:e2e` runs `dist/test/e2e/**` one file at a time. The end-to-end test builds the
  same app as `src/server.ts` (`buildApp`) and listens on a random local port, so it talks real
  HTTP; the clock is injected (`ManualClock`) so deadlines pass without waiting.
- Tests are compiled first: run `npm run build` after changing anything under `src/` or `test/`.

The security suite covers: 404 (never 403) for non-members on rooms, challenges, invitations and
media; refused submissions by non-members and late joiners; rejected `ownerId`, `authorId`,
`voterId`, `role` and similar body fields; 25 concurrent ballots storing one vote, idempotent
retries, `409 ALREADY_VOTED`; `410` after deadlines for ballots and submissions; `422` for
self-votes and blocked pairs; media revocation after leaving, removal, blocks, suspension and
account deletion; `415` for wrong magic bytes; `413` for oversize bodies, including chunked
uploads; GPS EXIF removal checked on the stored bytes; no deletion during voting; auto-hide at
three reporters; `audit_log` rows for every moderation command; account deletion that anonymizes
the name and keeps ballots with a null voter; `429` with `retryAfterSeconds`; invitation
expiry, revocation, exhaustion, full rooms and idempotent joins; and account deletion racing an
in-flight upload, ballot or join (a second connection holds a lock to force the interleaving).

## Local PostgreSQL

Tests need PostgreSQL 16. If you do not have a server, `scripts/local-postgres.sh` creates a
local cluster that trusts only `127.0.0.1` and `::1`:

```sh
scripts/local-postgres.sh init     # initdb + start + create photoduel_dev and photoduel_test
scripts/local-postgres.sh url      # prints the TEST_DATABASE_URL
scripts/local-postgres.sh status
scripts/local-postgres.sh stop     # stops only the cluster this script created
```

**It must run as a non-root user.** `initdb` and `postgres` refuse to run as root, and so does the
script. In a container where you are root, run it through an unprivileged account, for example
`runuser -u <user> -- env PHOTODUEL_PGDATA=/home/<user>/photoduel-pg scripts/local-postgres.sh init`.
The data directory defaults to `$HOME/.local/share/photoduel/pgdata` and the port to `55432`
(`PHOTODUEL_PGDATA`, `PHOTODUEL_PGPORT`, `PG_BIN` override them). If something already listens on
the port, the script refuses to start a second server; use that server instead or pick another
port.

In the shared development container a PostgreSQL 16 server already listens on `127.0.0.1:55432`
with the database `photoduel_test`; use it as `TEST_DATABASE_URL` and do not stop it.

## Not covered here

- Android compilation and the Android data layer are verified on GitHub Actions, not in this folder.
- No production database, private bucket, domain or TLS certificate is provisioned, and no
  provider budget alerts are configured (architecture.md section 14).
