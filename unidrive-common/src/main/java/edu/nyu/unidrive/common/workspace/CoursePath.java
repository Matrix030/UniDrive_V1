package edu.nyu.unidrive.common.workspace;

import java.nio.file.Path;
import java.util.Optional;

public record CoursePath(String term, String courseSlug, String assignmentId) {

    public static final String INSTRUCTOR_PUBLISH_DIR = "publish";
    public static final String INSTRUCTOR_SUBMISSIONS_DIR = "submissions";
    public static final String STUDENT_FILES_DIR = "files";
    public static final String STUDENT_SUBMISSION_DIR = "submission";
    public static final String FEEDBACK_DIR = "feedback";
    public static final String STUDENT_PREFIX = "student_";

    public CoursePath {
        if (term == null || term.isBlank()) {
            throw new IllegalArgumentException("term must be non-blank");
        }
        if (courseSlug == null || courseSlug.isBlank()) {
            throw new IllegalArgumentException("courseSlug must be non-blank");
        }
        if (assignmentId == null || assignmentId.isBlank()) {
            throw new IllegalArgumentException("assignmentId must be non-blank");
        }
    }

    public Path toRelativePath() {
        return Path.of(term, courseSlug, assignmentId);
    }

    public Path resolveAgainst(Path workspaceRoot) {
        return workspaceRoot.resolve(toRelativePath());
    }

    public Path publishDirIn(Path workspaceRoot, WorkspaceRole role) {
        return resolveAgainst(workspaceRoot).resolve(publishDirName(role));
    }

    public Path submissionsDirIn(Path workspaceRoot, WorkspaceRole role) {
        return resolveAgainst(workspaceRoot).resolve(submissionsDirName(role));
    }

    public Path feedbackDirIn(Path workspaceRoot) {
        return resolveAgainst(workspaceRoot).resolve(FEEDBACK_DIR);
    }

    public static String publishDirName(WorkspaceRole role) {
        return role == WorkspaceRole.STUDENT ? STUDENT_FILES_DIR : INSTRUCTOR_PUBLISH_DIR;
    }

    public static String submissionsDirName(WorkspaceRole role) {
        return role == WorkspaceRole.STUDENT ? STUDENT_SUBMISSION_DIR : INSTRUCTOR_SUBMISSIONS_DIR;
    }

    public static Optional<ParsedLocation> parseFromWorkspace(Path workspaceRoot, Path absoluteFilePath) {
        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        Path normalizedFile = absoluteFilePath.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot)) {
            return Optional.empty();
        }
        Path relative = normalizedRoot.relativize(normalizedFile);
        int depth = relative.getNameCount();
        if (depth < 5) {
            return Optional.empty();
        }

        String term = relative.getName(0).toString();
        String courseSlug = relative.getName(1).toString();
        String assignmentId = relative.getName(2).toString();
        String leafName = relative.getName(3).toString();

        Leaf leaf;
        if (INSTRUCTOR_PUBLISH_DIR.equals(leafName) || STUDENT_FILES_DIR.equals(leafName)) {
            leaf = Leaf.PUBLISH;
        } else if (INSTRUCTOR_SUBMISSIONS_DIR.equals(leafName) || STUDENT_SUBMISSION_DIR.equals(leafName)) {
            leaf = Leaf.SUBMISSIONS;
        } else if (FEEDBACK_DIR.equals(leafName)) {
            leaf = Leaf.FEEDBACK;
        } else {
            return Optional.empty();
        }

        CoursePath coursePath = new CoursePath(term, courseSlug, assignmentId);
        Optional<String> studentId = Optional.empty();
        Path file;

        if (leaf == Leaf.SUBMISSIONS) {
            String fourth = relative.getName(4).toString();
            if (fourth.startsWith(STUDENT_PREFIX)) {
                if (depth < 6) {
                    return Optional.empty();
                }
                studentId = Optional.of(fourth.substring(STUDENT_PREFIX.length()));
                file = relative.subpath(5, depth);
            } else {
                file = relative.subpath(4, depth);
            }
        } else {
            file = relative.subpath(4, depth);
        }

        return Optional.of(new ParsedLocation(coursePath, leaf, studentId, file));
    }

    public enum Leaf {
        PUBLISH,
        SUBMISSIONS,
        FEEDBACK
    }

    public record ParsedLocation(CoursePath coursePath, Leaf leaf, Optional<String> studentId, Path relativeFile) {
    }
}
