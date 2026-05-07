# University Drive

University Drive is a folder-based assignment sync system for an Intro to Java course project. It replaces the manual Brightspace zip-upload workflow with a local client that watches submission files, uploads them in the background, and syncs assignments and feedback through a Spring Boot server.

## Modules

- `unidrive-common`: shared DTOs, hash utility, sync status enum
- `unidrive-server`: Spring Boot REST API + SQLite metadata
- `unidrive-client`: JavaFX client + local SQLite state + background sync

## Implemented Workflow

### Student

1. Client bootstraps a local workspace with:
   - `Assignments/`
   - `Submissions/`
   - `Feedback/`
   - `sync-state.db`
2. Files added to `Submissions/` are detected by `WatchService`.
3. The client records them as `PENDING`, computes SHA-256, uploads them, and transitions through:
   - `UPLOADING`
   - `SYNCED` on success
   - `FAILED` on failure
4. The client polls the server for:
   - assignments into `Assignments/`
   - feedback into `Feedback/`

### Instructor

1. Publish an assignment through the server API.
2. Review synced submissions through the submissions API.
3. Return feedback through the server API.

## Quick Start

Requires Java 21+. Two terminals.

**Terminal 1 — server (port 8080):**

```bash
./mvnw -pl unidrive-server spring-boot:run
```

Wait until you see `Started UniDriveServerApplication`.

**Terminal 2 — client:**

```bash
cd unidrive-client && ../mvnw javafx:run
```

> The client must be launched from inside `unidrive-client/` (or the `javafx:run` plugin won't bind correctly).

To override the server URL: `../mvnw javafx:run -Djavafx.args="..."` or edit the default in `UniDriveClientApplication`.

### First launch flow

1. **Login screen** — enter any user ID (e.g. `rvg9395`), pick **Student** or **Instructor**, click Login. The server auto-creates the user if it doesn't exist.
2. **Folder picker** — click Browse and pick an empty folder for your workspace, set the assignment ID (default `assignment-1`), click Continue.
3. **Dashboard** — the client creates the role-specific subfolders and starts the background sync.

### Folders created per role

| Role | Folders |
|---|---|
| Student | `Assignments/` (read), `Submissions/` (drop), `Feedback/` (read) |
| Instructor | `Publish/` (drop), `Submissions/<studentId>/` (synced), `Feedback/<studentId>/` (drop) |

### Subsequent launches

Session is restored from `~/.unidrive/config.properties` — login + folder picker are skipped. Delete that file to start over.

### End-to-end demo

1. Start the server.
2. Start two clients in different workspaces — one Instructor, one Student (different user IDs).
3. **Instructor** drops `Assignment1.txt` into `Publish/`. The server publishes it.
4. **Student**: file appears in `Assignments/` within ~2 s.
5. **Student** drops `Solution.java` into `Submissions/`. Dashboard shows `PENDING` → `UPLOADING` → `SYNCED`.
6. **Instructor**: the submission appears under `Submissions/<studentId>/`.
7. **Instructor** drops a feedback file into `Feedback/<studentId>/`.
8. **Student**: feedback appears in `Feedback/`.

## Build & Test

```bash
./mvnw test           # run all 68 tests
./mvnw clean package  # build all module jars
```

## Smoke Test

Run the automated smoke test before a manual demo:

```bash
./scripts/smoke-test.sh
```

If port `18080` is busy, run it on another port:

```bash
UNIDRIVE_SMOKE_PORT=18081 ./scripts/smoke-test.sh
```

The smoke test installs fresh module artifacts, runs selected client workspace/session/sync checks, starts an isolated Spring Boot server, writes all temporary files under `target/smoke/`, and stops the server automatically when finished.

It verifies:

- student and instructor mock login success
- login errors for wrong password, unknown email, blank email, and blank password
- assignment publish, list, download, delete, and missing-assignment errors
- submission upload, list, filtered list, download, delete, SHA-256 mismatch, and missing-submission errors
- feedback upload, list, download, missing-submission, and missing-feedback errors
- downloaded assignment, submission, and feedback contents match the uploaded files
- selected client workspace, session, dashboard snapshot, and sync-service tests still pass

Useful options:

```bash
UNIDRIVE_SMOKE_SKIP_BUILD=1 ./scripts/smoke-test.sh          # reuse existing build artifacts
UNIDRIVE_SMOKE_CLEAN_BUILD=1 ./scripts/smoke-test.sh         # force clean install first
UNIDRIVE_SMOKE_SKIP_CLIENT_TESTS=1 ./scripts/smoke-test.sh   # only run server/API smoke checks
```

## Key API Endpoints

### Submissions

- `POST /api/v1/submissions/{assignmentId}`
- `GET /api/v1/submissions?assignmentId=...`
- `GET /api/v1/submissions?assignmentId=...&studentId=...`
- `GET /api/v1/submissions/{submissionId}/download`

### Assignments

- `POST /api/v1/instructor/assignments`
- `GET /api/v1/assignments`
- `GET /api/v1/assignments/{assignmentId}/download`

### Feedback

- `POST /api/v1/instructor/feedback/{submissionId}`
- `GET /api/v1/feedback?studentId=...`
- `GET /api/v1/feedback/{feedbackId}/download`

## Demo Flow

1. Start the server.
2. Start the client.
3. Publish an assignment using the assignment API.
4. Confirm the file appears in the student's `Assignments/` folder.
5. Drop a solution file into `Submissions/`.
6. Confirm the JavaFX client shows the file moving to `SYNCED`.
7. Confirm the server lists the submission.
8. Upload feedback using the feedback API.
9. Confirm the file appears in the student's `Feedback/` folder.

## Testing

The project uses TDD and currently verifies:

- hashing
- shared DTO contracts
- server upload/list/download flows
- assignment publish/list/download flows
- feedback upload/list/download flows
- client sync-state persistence
- workspace bootstrap
- submission watcher behavior
- local sync-state transitions
- upload client and upload transitions
- background sync loop
- assignment and feedback polling/download services
- client runtime startup lifecycle

Run all tests with:

```bash
./mvnw test
```
