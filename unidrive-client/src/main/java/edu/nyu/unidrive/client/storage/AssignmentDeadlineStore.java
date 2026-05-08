package edu.nyu.unidrive.client.storage;

import edu.nyu.unidrive.common.workspace.CoursePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public final class AssignmentDeadlineStore {

    public static final String DEADLINE_FILE_NAME = ".deadline";

    private AssignmentDeadlineStore() {
    }

    public static void writeDeadline(Path workspaceRoot, CoursePath coursePath, String deadline) throws IOException {
        Instant parsedDeadline = parse(deadline);
        Path deadlineFile = deadlineFile(workspaceRoot, coursePath);
        Files.createDirectories(deadlineFile.getParent());
        Files.writeString(deadlineFile, parsedDeadline.toString());
    }

    public static Optional<String> readDeadline(Path workspaceRoot, CoursePath coursePath) throws IOException {
        Path deadlineFile = deadlineFile(workspaceRoot, coursePath);
        if (!Files.isRegularFile(deadlineFile)) {
            return Optional.empty();
        }
        return Optional.of(parse(Files.readString(deadlineFile)).toString());
    }

    public static Instant parse(String deadline) {
        if (deadline == null || deadline.isBlank()) {
            throw new IllegalArgumentException("deadline must be non-blank");
        }
        try {
            return Instant.parse(deadline.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("deadline must be an ISO-8601 UTC instant", exception);
        }
    }

    private static Path deadlineFile(Path workspaceRoot, CoursePath coursePath) {
        return coursePath.resolveAgainst(workspaceRoot).resolve(DEADLINE_FILE_NAME);
    }
}
