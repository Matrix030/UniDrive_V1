-- Schema for fresh databases. AssignmentSchemaMigrator and
-- FeedbackRepository.initializeSchema() perform live ALTER TABLE migrations
-- for existing databases; keep them in sync when adding columns here.

CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    name TEXT,
    role TEXT,
    password_hash TEXT
);

CREATE TABLE IF NOT EXISTS assignments (
    id TEXT NOT NULL,
    file_name TEXT NOT NULL,
    term TEXT,
    course TEXT,
    title TEXT,
    deadline INTEGER,
    published_at INTEGER,
    file_path TEXT,
    hash TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at INTEGER,
    PRIMARY KEY (id, file_name)
);

CREATE TABLE IF NOT EXISTS submissions (
    id TEXT PRIMARY KEY,
    term TEXT,
    course TEXT,
    assignment_id TEXT,
    student_id TEXT,
    file_path TEXT,
    hash TEXT,
    submitted_at INTEGER,
    status TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at INTEGER
);

CREATE TABLE IF NOT EXISTS feedback (
    id TEXT PRIMARY KEY,
    submission_id TEXT,
    file_name TEXT,
    file_path TEXT,
    hash TEXT,
    returned_at INTEGER,
    version INTEGER NOT NULL DEFAULT 0,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at INTEGER
);

CREATE TABLE IF NOT EXISTS version_counter (
    table_name TEXT PRIMARY KEY,
    next_val INTEGER NOT NULL
);

INSERT OR IGNORE INTO version_counter (table_name, next_val) VALUES ('assignments', 1);
INSERT OR IGNORE INTO version_counter (table_name, next_val) VALUES ('submissions', 1);
INSERT OR IGNORE INTO version_counter (table_name, next_val) VALUES ('feedback', 1);
