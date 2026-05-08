package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.client.storage.ReceivedStateRecord;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.CoursePath.Leaf;
import edu.nyu.unidrive.common.workspace.CoursePath.ParsedLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

public final class ReceivedReconcileService {

    public static final String SOURCE_ASSIGNMENTS = "ASSIGNMENTS";
    public static final String SOURCE_FEEDBACK = "FEEDBACK";
    public static final String SOURCE_INSTRUCTOR_SUBMISSIONS = "INSTRUCTOR_SUBMISSIONS";

    private final ReceivedStateRepository receivedStateRepository;

    public ReceivedReconcileService(ReceivedStateRepository receivedStateRepository) {
        this.receivedStateRepository = receivedStateRepository;
    }

    public void reconcileWorkspaceRoot(Path workspaceRoot) {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> !isIgnoredFile(path))
                .forEach(path -> reconcileFile(workspaceRoot, path));
        } catch (IOException ignored) {
        }
    }

    private void reconcileFile(Path workspaceRoot, Path path) {
        Optional<ParsedLocation> parsed = CoursePath.parseFromWorkspace(workspaceRoot, path);
        if (parsed.isEmpty()) {
            return;
        }
        ParsedLocation location = parsed.get();
        String source;
        if (location.leaf() == Leaf.PUBLISH) {
            source = SOURCE_ASSIGNMENTS;
        } else if (location.leaf() == Leaf.SUBMISSIONS && location.studentId().isPresent()) {
            source = SOURCE_INSTRUCTOR_SUBMISSIONS;
        } else if (location.leaf() == Leaf.FEEDBACK && location.studentId().isEmpty()) {
            source = SOURCE_FEEDBACK;
        } else {
            return;
        }
        if (receivedStateRepository.findByLocalPath(path).isPresent()) {
            return;
        }
        receivedStateRepository.save(new ReceivedStateRecord(
            path,
            sourcePrefix(source) + path.getFileName(),
            safeHash(path),
            SyncStatus.SYNCED,
            safeLastModified(path),
            source
        ));
    }

    private boolean isIgnoredFile(Path path) {
        Path name = path.getFileName();
        return name != null && "desktop.ini".equalsIgnoreCase(name.toString());
    }

    private String sourcePrefix(String source) {
        return switch (source) {
            case SOURCE_ASSIGNMENTS -> "assignment:";
            case SOURCE_INSTRUCTOR_SUBMISSIONS -> "instructor-submission:";
            default -> "feedback:";
        };
    }

    private String safeHash(Path path) {
        try {
            return FileHasher.sha256Hex(path);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
