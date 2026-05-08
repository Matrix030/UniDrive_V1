#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SMOKE_DIR="$ROOT_DIR/target/smoke"
SMOKE_PORT="${UNIDRIVE_SMOKE_PORT:-18080}"
SERVER_URL="${UNIDRIVE_SMOKE_SERVER_URL:-http://localhost:$SMOKE_PORT}"
SERVER_DB="$SMOKE_DIR/unidrive-server-smoke.db"
SERVER_LOG="$SMOKE_DIR/server.log"
SERVER_PID=""
LAST_BODY=""
REQUEST_COUNT=0

TERM="fall2026"
COURSE="daa"
ASSIGNMENT_ID="smoke-hashing"
STUDENT_ID="rvg9395"
FUTURE_DEADLINE="2099-12-31T23:59:00Z"
PAST_DEADLINE="2000-01-01T00:00:00Z"

log() {
    printf '[smoke] %s\n' "$1"
}

pass() {
    printf '[pass] %s\n' "$1"
}

fail() {
    printf '[fail] %s\n' "$1" >&2
    if [ -n "$LAST_BODY" ] && [ -f "$LAST_BODY" ]; then
        printf '\nLast response body:\n' >&2
        sed 's/^/  /' "$LAST_BODY" >&2 || true
    fi
    if [ -f "$SERVER_LOG" ]; then
        printf '\nServer log tail:\n' >&2
        tail -80 "$SERVER_LOG" >&2 || true
    fi
    exit 1
}

cleanup() {
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
        log "stopping smoke server pid $SERVER_PID"
        kill "$SERVER_PID" >/dev/null 2>&1 || true
        wait "$SERVER_PID" >/dev/null 2>&1 || true
    fi
}

trap cleanup EXIT

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command missing: $1"
}

safe_name() {
    printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_'
}

new_body_path() {
    REQUEST_COUNT=$((REQUEST_COUNT + 1))
    printf '%s/http/%03d-%s.json' "$SMOKE_DIR" "$REQUEST_COUNT" "$(safe_name "$1")"
}

assert_status() {
    local label="$1"
    local expected="$2"
    local actual="$3"
    if [ "$actual" != "$expected" ]; then
        fail "$label expected HTTP $expected, got HTTP $actual"
    fi
    pass "$label returned HTTP $expected"
}

assert_json() {
    local label="${*: -1}"
    local jq_arg_count=$(($# - 1))
    local jq_args=("${@:1:$jq_arg_count}")
    jq -e "${jq_args[@]}" "$LAST_BODY" >/dev/null || fail "$label"
    pass "$label"
}

assert_file_equals() {
    local expected="$1"
    local actual="$2"
    local label="$3"
    cmp -s "$expected" "$actual" || fail "$label"
    pass "$label"
}

request() {
    local label="$1"
    local expected="$2"
    local method="$3"
    local path="$4"
    shift 4

    LAST_BODY=$(new_body_path "$label")
    local status
    status=$(curl --silent --show-error --output "$LAST_BODY" --write-out '%{http_code}' \
        --request "$method" "$@" "$SERVER_URL$path" || true)
    assert_status "$label" "$expected" "$status"
}

download() {
    local label="$1"
    local expected="$2"
    local path="$3"
    local output="$4"

    LAST_BODY="$output"
    local status
    status=$(curl --silent --show-error --output "$output" --write-out '%{http_code}' "$SERVER_URL$path" || true)
    assert_status "$label" "$expected" "$status"
}

sha256() {
    sha256sum "$1" | cut -d ' ' -f 1
}

build_project() {
    if [ "${UNIDRIVE_SMOKE_SKIP_BUILD:-0}" = "1" ]; then
        log "skipping build because UNIDRIVE_SMOKE_SKIP_BUILD=1"
        return
    fi
    log "building project and installing fresh module artifacts"
    if [ "${UNIDRIVE_SMOKE_CLEAN_BUILD:-0}" = "1" ]; then
        (cd "$ROOT_DIR" && ./mvnw clean install -DskipTests)
    else
        (cd "$ROOT_DIR" && ./mvnw install -DskipTests)
    fi
    pass "project built"
}

run_client_flow_tests() {
    if [ "${UNIDRIVE_SMOKE_SKIP_CLIENT_TESTS:-0}" = "1" ]; then
        log "skipping client flow tests because UNIDRIVE_SMOKE_SKIP_CLIENT_TESTS=1"
        return
    fi
    log "running client workspace/session/sync smoke tests"
    (cd "$ROOT_DIR" && ./mvnw -pl unidrive-client \
        -Dtest=SessionPersistenceTest,FolderBootstrapServiceTest,InstructorFolderBootstrapServiceTest,CurrentFolderWorkflowIntegrationTest,AssignmentSyncServiceTest,SubmissionUploadServiceTest,SubmissionReconcileServiceTest,SubmissionSyncStateServiceTest,SyncDashboardSnapshotServiceTest,FeedbackSyncServiceTest \
        test)
    pass "client workspace/session/sync smoke tests passed"
}

prepare_files() {
    log "resetting smoke workspace"
    rm -rf "$SMOKE_DIR"
    mkdir -p "$SMOKE_DIR/http" "$SMOKE_DIR/files" "$SMOKE_DIR/downloads" "$SMOKE_DIR/home"

    printf 'Hashing assignment for smoke test.\n' > "$SMOKE_DIR/files/hashing_assignment.txt"
    printf 'public class Solution { public static void main(String[] args) {} }\n' > "$SMOKE_DIR/files/Solution.java"
    printf 'Hashing feedback for smoke test.\n' > "$SMOKE_DIR/files/feedback.txt"
    printf 'This upload intentionally uses the wrong SHA.\n' > "$SMOKE_DIR/files/bad-hash.txt"
    pass "smoke workspace reset"
}

start_server() {
    if curl --silent --output /dev/null --max-time 1 "$SERVER_URL/api/v1/assignments?term=$TERM&course=$COURSE"; then
        fail "$SERVER_URL is already responding; stop the existing server or set UNIDRIVE_SMOKE_PORT"
    fi

    log "starting smoke server on $SERVER_URL"
    (
        cd "$ROOT_DIR"
        ./mvnw -pl unidrive-server spring-boot:run \
            -Dspring-boot.run.arguments="--server.port=$SMOKE_PORT --spring.datasource.url=jdbc:sqlite:$SERVER_DB"
    ) > "$SERVER_LOG" 2>&1 &
    SERVER_PID=$!

    for _ in $(seq 1 60); do
        if ! kill -0 "$SERVER_PID" >/dev/null 2>&1; then
            fail "smoke server exited before startup completed"
        fi
        local status
        status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
            "$SERVER_URL/api/v1/assignments?term=$TERM&course=$COURSE" || true)
        if [ "$status" = "200" ]; then
            pass "smoke server started"
            return
        fi
        sleep 1
    done
    fail "smoke server did not become ready"
}

smoke_login() {
    log "checking login flows"

    request "student login" 200 POST "/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data '{"email":"student@nyu.edu","password":"password123"}'
    assert_json '.status == "ok" and .data.userId == "rvg9395" and .data.email == "student@nyu.edu" and .data.role == "STUDENT" and (.data.accessToken | length > 0)' \
        "student login response contains expected identity"

    request "instructor login" 200 POST "/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data '{"email":"instructor@nyu.edu","password":"password123"}'
    assert_json '.status == "ok" and .data.userId == "instructor_rvg0000" and .data.email == "instructor@nyu.edu" and .data.role == "INSTRUCTOR" and (.data.accessToken | length > 0)' \
        "instructor login response contains expected identity"

    request "second student login" 200 POST "/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data '{"email":"student2@nyu.edu","password":"password123"}'
    assert_json '.status == "ok" and .data.userId == "ow2130" and .data.role == "STUDENT"' \
        "second student login response contains expected identity"

    request "third student login" 200 POST "/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data '{"email":"student3@nyu.edu","password":"password123"}'
    assert_json '.status == "ok" and .data.userId == "js1234" and .data.role == "STUDENT"' \
        "third student login response contains expected identity"

    request "second instructor login" 200 POST "/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data '{"email":"ta@nyu.edu","password":"password123"}'
    assert_json '.status == "ok" and .data.userId == "instructor_ow0000" and .data.role == "INSTRUCTOR"' \
        "second instructor login response contains expected identity"

    request "wrong password rejected" 401 POST "/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data '{"email":"student@nyu.edu","password":"wrong"}'
    assert_json '.status == "error"' "wrong password returns error payload"

    request "unknown email rejected" 401 POST "/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data '{"email":"missing@nyu.edu","password":"password123"}'
    assert_json '.status == "error"' "unknown email returns error payload"

    request "blank email rejected" 400 POST "/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data '{"email":"","password":"password123"}'
    assert_json '.status == "error"' "blank email returns error payload"

    request "blank password rejected" 400 POST "/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data '{"email":"student@nyu.edu","password":""}'
    assert_json '.status == "error"' "blank password returns error payload"
}

smoke_assignments() {
    log "checking assignment flows"

    local assignment_file="$SMOKE_DIR/files/hashing_assignment.txt"
    local assignment_sha
    assignment_sha=$(sha256 "$assignment_file")

    request "publish assignment" 200 POST "/api/v1/instructor/assignments/$TERM/$COURSE/$ASSIGNMENT_ID" \
        -F 'title=Smoke Hashing Assignment' \
        -F "deadline=$FUTURE_DEADLINE" \
        -F "file=@$assignment_file;filename=hashing_assignment.txt"
    assert_json --arg sha "$assignment_sha" --arg deadline "$FUTURE_DEADLINE" '.status == "ok" and .data.assignmentId == "smoke-hashing" and .data.term == "fall2026" and .data.course == "daa" and .data.fileName == "hashing_assignment.txt" and .data.sha256 == $sha and .data.deadline == $deadline' \
        "published assignment response matches upload"

    request "list assignments" 200 GET "/api/v1/assignments?term=$TERM&course=$COURSE"
    assert_json '.status == "ok" and any(.data[]; .assignmentId == "smoke-hashing" and .fileName == "hashing_assignment.txt")' \
        "list assignments includes published assignment"

    download "download assignment" 200 "/api/v1/assignments/$ASSIGNMENT_ID/download?fileName=hashing_assignment.txt" \
        "$SMOKE_DIR/downloads/hashing_assignment.txt"
    assert_file_equals "$assignment_file" "$SMOKE_DIR/downloads/hashing_assignment.txt" "downloaded assignment content matches"

    download "missing assignment download" 404 "/api/v1/assignments/missing/download?fileName=missing.txt" \
        "$SMOKE_DIR/downloads/missing_assignment.txt"
}

smoke_submissions_and_feedback() {
    log "checking submission and feedback flows"

    local submission_file="$SMOKE_DIR/files/Solution.java"
    local submission_sha
    submission_sha=$(sha256 "$submission_file")

    request "upload submission" 200 POST "/api/v1/submissions/$TERM/$COURSE/$ASSIGNMENT_ID" \
        -H "X-File-Sha256: $submission_sha" \
        -F "studentId=$STUDENT_ID" \
        -F "file=@$submission_file;filename=Solution.java"
    assert_json --arg sha "$submission_sha" '.status == "ok" and .data.assignmentId == "smoke-hashing" and .data.studentId == "rvg9395" and .data.fileName == "Solution.java" and .data.sha256 == $sha' \
        "submission upload response matches file"
    local submission_id
    submission_id=$(jq -r '.data.submissionId' "$LAST_BODY")
    [ -n "$submission_id" ] && [ "$submission_id" != "null" ] || fail "submission id missing from upload response"

    request "list submissions" 200 GET "/api/v1/submissions?term=$TERM&course=$COURSE&assignmentId=$ASSIGNMENT_ID"
    assert_json --arg id "$submission_id" 'any(.data[]; .submissionId == $id and .studentId == "rvg9395")' \
        "list submissions includes uploaded submission"

    request "list submissions filtered" 200 GET "/api/v1/submissions?term=$TERM&course=$COURSE&assignmentId=$ASSIGNMENT_ID&studentId=$STUDENT_ID"
    assert_json --arg id "$submission_id" '(.data | length) == 1 and .data[0].submissionId == $id' \
        "filtered submissions returns student submission"

    download "download submission" 200 "/api/v1/submissions/$submission_id/download" "$SMOKE_DIR/downloads/Solution.java"
    assert_file_equals "$submission_file" "$SMOKE_DIR/downloads/Solution.java" "downloaded submission content matches"

    request "bad submission sha rejected" 422 POST "/api/v1/submissions/$TERM/$COURSE/$ASSIGNMENT_ID" \
        -H 'X-File-Sha256: 0000000000000000000000000000000000000000000000000000000000000000' \
        -F "studentId=$STUDENT_ID" \
        -F "file=@$SMOKE_DIR/files/bad-hash.txt;filename=bad-hash.txt"
    assert_json '.status == "error"' "bad submission sha returns error payload"

    request "late assignment publish" 200 POST "/api/v1/instructor/assignments/$TERM/$COURSE/smoke-late" \
        -F 'title=Smoke Late Assignment' \
        -F "deadline=$PAST_DEADLINE" \
        -F "file=@$SMOKE_DIR/files/hashing_assignment.txt;filename=late_assignment.txt"

    request "late submission rejected" 409 POST "/api/v1/submissions/$TERM/$COURSE/smoke-late" \
        -H "X-File-Sha256: $submission_sha" \
        -F "studentId=$STUDENT_ID" \
        -F "file=@$submission_file;filename=Solution.java"
    assert_json '.status == "error" and .message == "Assignment deadline has passed."' "late submission returns deadline error"

    download "missing submission download" 404 "/api/v1/submissions/missing/download" "$SMOKE_DIR/downloads/missing_submission.txt"

    local feedback_file="$SMOKE_DIR/files/feedback.txt"
    local feedback_sha
    feedback_sha=$(sha256 "$feedback_file")

    request "upload feedback" 200 POST "/api/v1/instructor/feedback/$submission_id" \
        -F "file=@$feedback_file;filename=feedback.txt"
    assert_json --arg submission "$submission_id" --arg sha "$feedback_sha" '.status == "ok" and .data.submissionId == $submission and .data.term == "fall2026" and .data.course == "daa" and .data.assignmentId == "smoke-hashing" and .data.studentId == "rvg9395" and .data.fileName == "feedback.txt" and .data.sha256 == $sha' \
        "feedback upload response matches file"
    local feedback_id
    feedback_id=$(jq -r '.data.feedbackId' "$LAST_BODY")
    [ -n "$feedback_id" ] && [ "$feedback_id" != "null" ] || fail "feedback id missing from upload response"

    request "list feedback" 200 GET "/api/v1/feedback?studentId=$STUDENT_ID"
    assert_json --arg id "$feedback_id" 'any(.data[]; .feedbackId == $id and .term == "fall2026" and .course == "daa" and .assignmentId == "smoke-hashing" and .studentId == "rvg9395")' \
        "list feedback includes uploaded feedback"

    download "download feedback" 200 "/api/v1/feedback/$feedback_id/download" "$SMOKE_DIR/downloads/feedback.txt"
    assert_file_equals "$feedback_file" "$SMOKE_DIR/downloads/feedback.txt" "downloaded feedback content matches"

    request "missing feedback submission rejected" 404 POST "/api/v1/instructor/feedback/missing" \
        -F "file=@$feedback_file;filename=feedback.txt"

    download "missing feedback download" 404 "/api/v1/feedback/missing/download" "$SMOKE_DIR/downloads/missing_feedback.txt"

    request "delete submission" 200 DELETE "/api/v1/submissions/$submission_id"
    assert_json '.status == "ok"' "delete submission returns success payload"

    request "delete late assignment" 200 DELETE "/api/v1/instructor/assignments/smoke-late?fileName=late_assignment.txt"
    assert_json '.status == "ok"' "delete late assignment returns success payload"

    request "missing submission delete" 404 DELETE "/api/v1/submissions/missing"
}

smoke_assignment_delete() {
    log "checking assignment delete flows"

    request "delete assignment" 200 DELETE "/api/v1/instructor/assignments/$ASSIGNMENT_ID?fileName=hashing_assignment.txt"
    assert_json '.status == "ok"' "delete assignment returns success payload"

    request "list assignments after delete" 200 GET "/api/v1/assignments?term=$TERM&course=$COURSE"
    assert_json 'all(.data[]; .assignmentId != "smoke-hashing" or .fileName != "hashing_assignment.txt")' \
        "deleted assignment no longer appears in list"

    request "missing assignment delete" 404 DELETE "/api/v1/instructor/assignments/missing?fileName=missing.txt"
}

main() {
    require_command curl
    require_command jq
    require_command sha256sum
    require_command java

    build_project
    run_client_flow_tests
    prepare_files
    start_server
    smoke_login
    smoke_assignments
    smoke_submissions_and_feedback
    smoke_assignment_delete

    pass "smoke test complete"
    log "server log: $SERVER_LOG"
    log "smoke artifacts: $SMOKE_DIR"
}

main "$@"
