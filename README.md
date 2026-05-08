# University Drive

University Drive is a folder-based assignment sync system for an Intro to Java course project. It replaces the manual Brightspace zip-upload workflow with a local client that watches submission files, uploads them in the background, and syncs assignments and feedback through a Spring Boot server.

This README is the final project report. It includes setup instructions, demo credentials, verification steps, and the advanced topics used in the project.

## API Key Requirement

No external API key is required for this project. Authentication is implemented as a mocked SSO-style login module for the course project, so the first screen asks for an email and password instead of an API key.

Use one of these demo accounts. Every account uses password `password123`.

| Demo user | Role | Login email | App user ID | Demo workspace folder |
| ---------- | ---- | ----------- | ----------- | --------------------- |
| Main instructor | Instructor | `instructor@nyu.edu` | `instructor_rvg0000` | `demo-workspace/instructor_rvg0000` |
| TA / second instructor | Instructor | `ta@nyu.edu` | `instructor_ow0000` | `demo-workspace/instructor_ow0000` |
| Student 1 | Student | `student@nyu.edu` | `rvg9395` | `demo-workspace/student_rvg9395` |
| Student 2 | Student | `student2@nyu.edu` | `ow2130` | `demo-workspace/student_ow2130` |
| Student 3 | Student | `student3@nyu.edu` | `js1234` | `demo-workspace/student_js1234` |

Login aliases also work: `rvg0000@nyu.edu` for the main instructor, `ow0000@nyu.edu` for the TA, `rvg9395@nyu.edu` for Student 1, `ow2130@nyu.edu` for Student 2, and `js1234@nyu.edu` for Student 3.

## Modules

- `unidrive-common`: shared DTOs, hash utility, sync status enum
- `unidrive-server`: Spring Boot REST API + SQLite metadata
- `unidrive-client`: JavaFX client + local SQLite state + background sync

## Advanced Topics Used

### 1. Client-server REST architecture

The project is split into a JavaFX desktop client and a Spring Boot REST server. The client talks to server endpoints through `RestAuthApiClient`, `RestAssignmentApiClient`, `RestSubmissionApiClient`, and `RestFeedbackApiClient`. Shared request/response contracts live in `unidrive-common` DTOs.

### 2. Persistent storage with SQLite

The server stores users, assignments, submissions, and feedback metadata in SQLite. The client also uses local SQLite databases for sync state and received-file tracking, so it can restore dashboard status across app launches.

### 3. Background file synchronization

The client uses Java `WatchService`, polling services, and background sync loops to detect local file changes, upload submissions/published assignments, and download assignments, submissions, and feedback without blocking the UI.

### 4. JavaFX desktop UI

The project includes a role-aware JavaFX interface with login, folder selection, student dashboard, instructor dashboard, status counts, file tables, and a switch-user flow.

### 5. File integrity verification

Uploads include SHA-256 hashes. The server recomputes file hashes and rejects mismatches with a `422` response, preventing corrupted or unexpected file uploads from being accepted.

### 6. Automated smoke testing

The repository includes `scripts/smoke-test.sh`, which builds the project, starts an isolated server, exercises the main API flows, checks expected error cases, verifies downloaded file contents, and runs selected client sync/session tests.

## Implemented Workflow

### Student

1. Client bootstraps a local workspace with:
   - term folders such as `fall2026/`
   - course folders such as `fall2026/daa/`
   - assignment folders created by sync, each with `files/`, `submission/`, and `feedback/`
   - `sync-state.db`
2. Files added to an assignment's `submission/` folder are detected by `WatchService`.
3. The client records them as `PENDING`, computes SHA-256, uploads them, and transitions through:
   - `UPLOADING`
   - `SYNCED` on success
   - `FAILED` on failure
4. The client polls the server for:
   - assignment files into assignment `files/` folders
   - feedback returned by the instructor into assignment `feedback/` folders

### Instructor

1. Publish an assignment by dropping files into an assignment's `publish/` folder.
2. Review synced submissions under `submissions/student_<id>/`.
3. Return feedback by dropping files into `submissions/student_<id>/feedback/`.

## Quick Start

Requires Java 21+. The project includes Maven Wrapper, so Maven does not need to be installed separately.

## Fresh Machine Setup

If the machine has nothing installed, install Git and Java 21 first. Maven is not required because this repository includes `mvnw`.

### Linux: Ubuntu/Debian

```bash
sudo apt update
sudo apt install -y git openjdk-21-jdk curl jq
```

### macOS

Install Homebrew from `https://brew.sh/`, then run:

```bash
brew install git openjdk@21 curl jq
```

If Java 21 is not found after installation, add it to your shell path using Homebrew's printed instructions. On Apple Silicon Macs, this is usually:

```bash
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
```

On Intel Macs, this is usually:

```bash
export PATH="/usr/local/opt/openjdk@21/bin:$PATH"
```

### Windows

Install these tools:

- Git for Windows: `https://git-scm.com/download/win`
- Java 21 JDK, such as Temurin 21: `https://adoptium.net/temurin/releases/?version=21`

Then open PowerShell or Git Bash and verify:

```bash
git --version
java -version
```

The Java version should say `21` or newer.

### Clone And Run

Clone the repository and enter it:

```bash
git clone <your-github-repo-url>
cd uniDrive
```

If using Linux or macOS and `mvnw` is not executable, run:

```bash
chmod +x mvnw scripts/smoke-test.sh scripts/fresh-demo.sh
```

Verify Java:

```bash
java -version
```

Build everything once:

```bash
./mvnw clean install
```

Run the smoke test to confirm the machine is ready:

```bash
./scripts/smoke-test.sh
```

If `./scripts/smoke-test.sh` says `jq` is missing, install `jq` using the package manager commands above. `jq` is only needed for the smoke-test script, not for manually running the app.

From the repository root, first install fresh module artifacts:

```bash
./mvnw clean install
```

This step is important because the client and server both depend on `unidrive-common`.

### Terminal 1: Server

Run the Spring Boot server on port `8080`:

```bash
./mvnw -pl unidrive-server spring-boot:run
```

Wait until you see `Started UniDriveServerApplication`.

### Terminal 2: Instructor Client

Run the JavaFX client for the instructor. The separate `userHome` keeps this session separate from the student client:

```bash
./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/instructor
```

Log in with `instructor@nyu.edu` / `password123`, then choose a workspace folder such as `demo-workspace/manual-instructor`.

### Terminal 3: Student Client

Run another JavaFX client for the student in a third terminal:

```bash
./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/student
```

Log in with `student@nyu.edu` / `password123`, then choose a different workspace folder such as `demo-workspace/manual-student`.

For a larger demo, start more client terminals with separate homes and workspaces. Log in as `student2@nyu.edu`, `student3@nyu.edu`, or `ta@nyu.edu` to simulate multiple students and another instructor/TA.

To override the server URL:

```bash
./mvnw -pl unidrive-client javafx:run -Dunidrive.serverBaseUrl=http://localhost:8080
```

### First launch flow

1. **Login screen**: enter one of the demo email/password pairs from the API Key Requirement section.
2. **Folder picker**: click Browse and pick a folder for the local workspace.
3. **Dashboard**: the client creates the role-specific subfolders and starts background sync.

### Folders created per role

| Role       | Folders                                                                                                                                         |
| ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Student    | `<term>/<course>/<assignment>/files/` for received assignment files, `<term>/<course>/<assignment>/submission/` for student uploads, `<term>/<course>/<assignment>/feedback/` for returned feedback |
| Instructor | `<term>/<course>/<assignment>/publish/` for assignment files, `<term>/<course>/<assignment>/submissions/student_<id>/` for received submissions, `<term>/<course>/<assignment>/submissions/student_<id>/feedback/` for feedback returned to that student |

### Subsequent launches

Session is restored from `~/.unidrive/config.properties`, so login and folder picker are skipped on later launches. Delete that file to start over:

```bash
rm -f ~/.unidrive/config.properties
```

To reset the server database:

```bash
rm -f unidrive-server/target/*.db
```

### Manual Three-Terminal Demo

Use three terminals so the server, instructor client, and student client all stay running:

1. **Terminal 1**: start the server with `./mvnw -pl unidrive-server spring-boot:run`.
2. **Terminal 2**: start the instructor client with `./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/instructor`.
3. **Terminal 3**: start the student client with `./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/student`.
4. In the instructor client, log in with `instructor@nyu.edu` / `password123` and choose an instructor workspace folder.
5. In the student client, log in with `student@nyu.edu` / `password123` and choose a different student workspace folder.
6. In the instructor dashboard, create an assignment slot for a course and assignment id.
7. Drop or update an assignment file in the instructor workspace under `<term>/<course>/<assignment>/publish/`.
8. Watch the student workspace: the file should sync into `<term>/<course>/<assignment>/files/`.
9. Drop or update a student solution file in the student workspace under `<term>/<course>/<assignment>/submission/`.
10. Watch the student dashboard move the file through sync status, and watch the instructor workspace receive it under `<term>/<course>/<assignment>/submissions/student_rvg9395/`.
11. Drop or update a feedback file in the instructor workspace under `<term>/<course>/<assignment>/submissions/student_rvg9395/feedback/`.
12. Watch the student client receive the feedback under `<term>/<course>/<assignment>/feedback/` after background sync.

You can keep the folders open in your file manager while both clients run. As you drop or update files in the watched folders, the background sync services upload/download them through the server and the dashboard counts update automatically.

To run two clients at the same time without sharing saved sessions, use separate fake home directories:

```bash
./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/instructor
./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/student
```

For a multi-user demo, use one unique `userHome` per client:

```bash
./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/instructor
./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/ta
./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/student1
./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/student2
./mvnw -pl unidrive-client javafx:run -Dunidrive.userHome=target/demo-home/student3
```

## Build & Test

```bash
./mvnw test           # run all unit and integration tests
./mvnw clean package  # build all module jars
```

## Fresh Demo Reset

Before a live demo, run this script to reset project state and rebuild from scratch:

```bash
./scripts/fresh-demo.sh
```

It does three things:

1. Removes the server SQLite database and stored upload artifacts.
2. Recreates `demo-workspace/` with one folder for each mocked user: `instructor_rvg0000`, `instructor_ow0000`, `student_rvg9395`, `student_ow2130`, and `student_js1234`.
3. Runs `./mvnw clean install` from the repository root.

To place the demo workspace somewhere else:

```bash
UNIDRIVE_DEMO_WORKSPACE=/path/to/demo-workspace ./scripts/fresh-demo.sh
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

### Auth

- `POST /api/v1/auth/login`

### Submissions

- `POST /api/v1/submissions/{term}/{course}/{assignmentId}`
- `GET /api/v1/submissions?term=...&course=...&assignmentId=...`
- `GET /api/v1/submissions?term=...&course=...&assignmentId=...&studentId=...`
- `GET /api/v1/submissions/{submissionId}/download`
- `DELETE /api/v1/submissions/{submissionId}`

### Assignments

- `POST /api/v1/instructor/assignments/{term}/{course}/{assignmentId}`
- `GET /api/v1/assignments?term=...&course=...`
- `GET /api/v1/assignments/{assignmentId}/download?fileName=...`
- `DELETE /api/v1/instructor/assignments/{assignmentId}?fileName=...`

### Feedback

- `POST /api/v1/instructor/feedback/{submissionId}`
- `GET /api/v1/feedback?studentId=...`
- `GET /api/v1/feedback/{feedbackId}/download`

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
