#!/usr/bin/env bash
# Local PostgreSQL 16 cluster for PhotoDuel backend development and tests.
#
# NON-ROOT REQUIREMENT: initdb and postgres refuse to run as root (uid 0). Run this script as an
# ordinary user. In a container where you are root, create or pick an unprivileged account and run
# it through that account, for example:
#
#   useradd --create-home pgdev                       # once
#   runuser -u pgdev -- env PHOTODUEL_PGDATA=/home/pgdev/photoduel-pg ./scripts/local-postgres.sh init
#
# The cluster trusts connections from 127.0.0.1 and ::1 only, with no password. It is for local
# development and tests, never for production.
#
# Usage: scripts/local-postgres.sh <init|start|stop|status|url|help>
#   init    create the cluster (if absent), start it, create the photoduel_dev and photoduel_test databases
#   start   start an existing cluster
#   stop    stop the cluster started from PHOTODUEL_PGDATA (and only that one)
#   status  show whether that cluster is running
#   url     print the TEST_DATABASE_URL for this cluster
#
# Environment:
#   PHOTODUEL_PGDATA  data directory      (default: $HOME/.local/share/photoduel/pgdata)
#   PHOTODUEL_PGPORT  TCP port            (default: 55432)
#   PG_BIN            PostgreSQL 16 bin   (default: /usr/lib/postgresql/16/bin)
set -euo pipefail

PG_BIN="${PG_BIN:-/usr/lib/postgresql/16/bin}"
PGDATA_DIR="${PHOTODUEL_PGDATA:-${HOME:-/nonexistent}/.local/share/photoduel/pgdata}"
PGPORT_NUM="${PHOTODUEL_PGPORT:-55432}"
SOCKET_DIR="$(dirname "$PGDATA_DIR")/socket"
LOG_FILE="$(dirname "$PGDATA_DIR")/postgres.log"

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed -e '/^set -euo/d' -e 's/^# \{0,1\}//'
}

die() {
  echo "local-postgres: $*" >&2
  exit 1
}

require_non_root() {
  if [ "$(id -u)" = "0" ]; then
    die "refusing to run as root: initdb and postgres do not run as uid 0.
Run as an unprivileged user, for example:
  runuser -u <user> -- env PHOTODUEL_PGDATA=/home/<user>/photoduel-pg $0 $1"
  fi
}

require_binaries() {
  for b in initdb pg_ctl pg_isready psql; do
    [ -x "$PG_BIN/$b" ] || die "$PG_BIN/$b not found; install PostgreSQL 16 or set PG_BIN"
  done
}

port_in_use() {
  "$PG_BIN/pg_isready" -q -h 127.0.0.1 -p "$PGPORT_NUM" >/dev/null 2>&1
}

is_ours_running() {
  [ -f "$PGDATA_DIR/postmaster.pid" ] && "$PG_BIN/pg_ctl" -D "$PGDATA_DIR" status >/dev/null 2>&1
}

start_cluster() {
  if is_ours_running; then
    echo "local-postgres: already running ($PGDATA_DIR, port $PGPORT_NUM)"
    return 0
  fi
  if port_in_use; then
    die "port $PGPORT_NUM already has a PostgreSQL server that this script did not start.
Use that server (see README.md) or pick another port with PHOTODUEL_PGPORT."
  fi
  mkdir -p "$SOCKET_DIR"
  chmod 700 "$SOCKET_DIR"
  "$PG_BIN/pg_ctl" -D "$PGDATA_DIR" -l "$LOG_FILE" -w \
    -o "-p $PGPORT_NUM -k $SOCKET_DIR -c listen_addresses=127.0.0.1" start
}

create_db() {
  local name="$1"
  local exists
  exists="$("$PG_BIN/psql" -h 127.0.0.1 -p "$PGPORT_NUM" -U postgres -d postgres -Atc \
    "SELECT 1 FROM pg_database WHERE datname = '$name'")"
  if [ "$exists" != "1" ]; then
    "$PG_BIN/psql" -h 127.0.0.1 -p "$PGPORT_NUM" -U postgres -d postgres -qc "CREATE DATABASE $name"
    echo "local-postgres: created database $name"
  fi
}

cmd="${1:-help}"
case "$cmd" in
  init)
    require_non_root init
    require_binaries
    if [ ! -f "$PGDATA_DIR/PG_VERSION" ]; then
      mkdir -p "$PGDATA_DIR"
      chmod 700 "$PGDATA_DIR"
      "$PG_BIN/initdb" -D "$PGDATA_DIR" -U postgres --auth=trust --encoding=UTF8 --locale=C >/dev/null
      echo "local-postgres: initialised $PGDATA_DIR"
    fi
    start_cluster
    create_db photoduel_dev
    create_db photoduel_test
    echo "TEST_DATABASE_URL=postgres://postgres@127.0.0.1:$PGPORT_NUM/photoduel_test"
    echo "DATABASE_URL=postgres://postgres@127.0.0.1:$PGPORT_NUM/photoduel_dev"
    ;;
  start)
    require_non_root start
    require_binaries
    [ -f "$PGDATA_DIR/PG_VERSION" ] || die "no cluster at $PGDATA_DIR; run '$0 init' first"
    start_cluster
    ;;
  stop)
    require_non_root stop
    require_binaries
    if is_ours_running; then
      "$PG_BIN/pg_ctl" -D "$PGDATA_DIR" -m fast -w stop
    else
      echo "local-postgres: no running cluster at $PGDATA_DIR (nothing else is touched)"
    fi
    ;;
  status)
    require_binaries
    if is_ours_running; then
      echo "running: $PGDATA_DIR on port $PGPORT_NUM"
    else
      echo "not running: $PGDATA_DIR"
      exit 3
    fi
    ;;
  url)
    echo "postgres://postgres@127.0.0.1:$PGPORT_NUM/photoduel_test"
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
