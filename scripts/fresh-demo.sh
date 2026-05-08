#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DEMO_WORKSPACE="${UNIDRIVE_DEMO_WORKSPACE:-$ROOT_DIR/demo-workspace}"

USERS=(
    "instructor_rvg0000"
    "instructor_ow0000"
    "student_rvg9395"
    "student_ow2130"
    "student_js1234"
)

log() {
    printf '[fresh-demo] %s\n' "$1"
}

fail() {
    printf '[fresh-demo] failed: %s\n' "$1" >&2
    exit 1
}

reset_server_state() {
    log "removing server database and stored upload artifacts"
    rm -f "$ROOT_DIR/target/unidrive-server.db" "$ROOT_DIR/target/unidrive-server.db-shm" "$ROOT_DIR/target/unidrive-server.db-wal"
    rm -f "$ROOT_DIR"/unidrive-server/target/*.db "$ROOT_DIR"/unidrive-server/target/*.db-shm "$ROOT_DIR"/unidrive-server/target/*.db-wal
    rm -rf "$ROOT_DIR/unidrive-server/target/storage"
}

reset_demo_workspace() {
    if [ -z "$DEMO_WORKSPACE" ] || [ "$DEMO_WORKSPACE" = "/" ] || [ "$DEMO_WORKSPACE" = "$ROOT_DIR" ]; then
        fail "refusing to reset unsafe demo workspace path: $DEMO_WORKSPACE"
    fi

    log "recreating demo workspace at $DEMO_WORKSPACE"
    rm -rf "$DEMO_WORKSPACE"
    mkdir -p "$DEMO_WORKSPACE"

    for user in "${USERS[@]}"; do
        mkdir -p "$DEMO_WORKSPACE/$user"
        log "created $DEMO_WORKSPACE/$user"
    done
}

build_from_scratch() {
    log "building project from scratch with ./mvnw clean install"
    (cd "$ROOT_DIR" && ./mvnw clean install)
}

main() {
    reset_server_state
    reset_demo_workspace
    build_from_scratch
    log "fresh demo setup complete"
}

main "$@"
