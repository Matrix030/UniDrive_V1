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
    published_at INTEGER,
    file_path TEXT,
    hash TEXT,
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
    status TEXT
);

CREATE TABLE IF NOT EXISTS feedback (
    id TEXT PRIMARY KEY,
    submission_id TEXT,
    file_name TEXT,
    file_path TEXT,
    hash TEXT,
    returned_at INTEGER
);
