package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.CoursePath.Leaf;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class FeedbackDirectoryWatcher implements Closeable {

    private final Path workspaceRoot;
    private final WatchService watchService;
    private final Map<WatchKey, Path> watchedDirsByKey = new HashMap<>();

    public FeedbackDirectoryWatcher(Path workspaceRoot) throws IOException {
        this.workspaceRoot = workspaceRoot;
        this.watchService = FileSystems.getDefault().newWatchService();
        registerRecursively(workspaceRoot);
    }

    public List<SubmissionFileEvent> pollEvents(Duration timeout) {
        try {
            WatchKey firstKey = watchService.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (firstKey == null) {
                return List.of();
            }

            Map<Path, SubmissionFileEventType> eventTypesByPath = new LinkedHashMap<>();
            collectEvents(firstKey, eventTypesByPath);

            WatchKey nextKey;
            while ((nextKey = watchService.poll()) != null) {
                collectEvents(nextKey, eventTypesByPath);
            }

            List<SubmissionFileEvent> events = new ArrayList<>();
            for (Map.Entry<Path, SubmissionFileEventType> entry : eventTypesByPath.entrySet()) {
                events.add(new SubmissionFileEvent(entry.getKey(), entry.getValue()));
            }
            return events;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for feedback events.", exception);
        }
    }

    @Override
    public void close() throws IOException {
        watchService.close();
    }

    private void collectEvents(WatchKey watchKey, Map<Path, SubmissionFileEventType> eventTypesByPath) {
        Path watchedDir = watchedDirsByKey.get(watchKey);
        for (WatchEvent<?> event : watchKey.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                Path overflowSubtree = watchedDir == null ? workspaceRoot : watchedDir;
                eventTypesByPath.put(overflowSubtree, SubmissionFileEventType.OVERFLOW);
                continue;
            }
            Path relativePath = (Path) event.context();
            Path absolutePath = (watchedDir == null ? workspaceRoot : watchedDir).resolve(relativePath);

            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(absolutePath)) {
                try {
                    registerRecursively(absolutePath);
                } catch (IOException ignored) {
                }
                continue;
            }

            if (event.kind() != StandardWatchEventKinds.ENTRY_DELETE && !Files.isRegularFile(absolutePath)) {
                continue;
            }
            if (!isInstructorFeedbackFile(absolutePath)) {
                continue;
            }

            SubmissionFileEventType newType = mapEventType(event.kind());
            SubmissionFileEventType existingType = eventTypesByPath.get(absolutePath);
            if (existingType == SubmissionFileEventType.CREATED
                || (newType == SubmissionFileEventType.MODIFIED && existingType != null)) {
                continue;
            }
            eventTypesByPath.put(absolutePath, newType);
        }
        watchKey.reset();
    }

    private boolean isInstructorFeedbackFile(Path absolutePath) {
        return CoursePath.parseFromWorkspace(workspaceRoot, absolutePath)
            .map(parsed -> parsed.leaf() == Leaf.FEEDBACK && parsed.studentId().isPresent())
            .orElse(false);
    }

    private SubmissionFileEventType mapEventType(WatchEvent.Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            return SubmissionFileEventType.CREATED;
        }
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            return SubmissionFileEventType.DELETED;
        }
        return SubmissionFileEventType.MODIFIED;
    }

    private void registerRecursively(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
                );
                watchedDirsByKey.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
