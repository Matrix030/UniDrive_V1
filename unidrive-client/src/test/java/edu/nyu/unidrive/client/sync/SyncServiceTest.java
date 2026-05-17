package edu.nyu.unidrive.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.nyu.unidrive.client.net.DownloadedFile;
import edu.nyu.unidrive.client.net.SubmissionApiClient;
import edu.nyu.unidrive.client.storage.SyncStateRepository;
import edu.nyu.unidrive.common.dto.SubmissionSummaryResponse;
import edu.nyu.unidrive.common.dto.SubmissionUploadResponse;
import edu.nyu.unidrive.common.workspace.CoursePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncServiceTest {

    @Test
    void processOnceDoesNotReconcileWorkspaceForNormalEvents(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        SyncStateRepository repository = new SyncStateRepository(tempDir.resolve("sync-state.db"));
        CountingReconcileService reconcile = new CountingReconcileService(repository);

        List<SubmissionFileEvent> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Path submission = workspaceRoot.resolve("fall2026/daa/hw1/submissions/student/file" + i + ".java");
            Files.createDirectories(submission.getParent());
            Files.writeString(submission, "x");
            batch.add(new SubmissionFileEvent(submission, SubmissionFileEventType.CREATED));
        }

        SyncService service = newService(workspaceRoot, repository, reconcile, new ScriptedEventSource(batch));
        try {
            service.processOnce();
            assertEquals(0, reconcile.fullCount.get(),
                "in-loop full reconcile should be removed");
            assertEquals(0, reconcile.subtreeCount.get(),
                "subtree reconcile should only fire on OVERFLOW");
        } finally {
            service.close();
        }
    }

    @Test
    void overflowEventTriggersTargetedReconcileForAffectedSubtreeOnly(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Path affectedSubtree = workspaceRoot.resolve("fall2026/daa/hw1");
        Files.createDirectories(affectedSubtree);

        SyncStateRepository repository = new SyncStateRepository(tempDir.resolve("sync-state.db"));
        CountingReconcileService reconcile = new CountingReconcileService(repository);

        List<SubmissionFileEvent> batch = List.of(
            new SubmissionFileEvent(affectedSubtree, SubmissionFileEventType.OVERFLOW)
        );

        SyncService service = newService(workspaceRoot, repository, reconcile, new ScriptedEventSource(batch));
        try {
            service.processOnce();
            assertEquals(0, reconcile.fullCount.get(),
                "OVERFLOW should not trigger a workspace-wide reconcile");
            assertEquals(1, reconcile.subtreeCount.get(),
                "OVERFLOW should trigger one targeted reconcile");
            assertEquals(1, reconcile.subtreesReconciled.size());
            assertTrue(reconcile.subtreesReconciled.contains(affectedSubtree),
                "subtree reconcile should run on the OVERFLOW event's path");
        } finally {
            service.close();
        }
    }

    private SyncService newService(
        Path workspaceRoot,
        SyncStateRepository repository,
        SubmissionReconcileService reconcile,
        SubmissionEventSource eventSource
    ) {
        SubmissionSyncStateService syncStateService = new SubmissionSyncStateService(repository);
        SubmissionUploadService uploadService = new SubmissionUploadService(
            repository, new NoopSubmissionApiClient(), workspaceRoot);
        return new SyncService(
            eventSource,
            syncStateService,
            uploadService,
            reconcile,
            repository,
            workspaceRoot,
            "student-1",
            Duration.ofMillis(10)
        );
    }

    private static final class CountingReconcileService extends SubmissionReconcileService {
        final AtomicInteger fullCount = new AtomicInteger();
        final AtomicInteger subtreeCount = new AtomicInteger();
        final List<Path> subtreesReconciled = new ArrayList<>();

        CountingReconcileService(SyncStateRepository repository) {
            super(repository);
        }

        @Override
        public void reconcileExistingSubmissions(Path workspaceRoot) {
            fullCount.incrementAndGet();
        }

        @Override
        public void reconcileSubtree(Path workspaceRoot, Path subtree) {
            subtreeCount.incrementAndGet();
            subtreesReconciled.add(subtree);
        }
    }

    private static final class ScriptedEventSource implements SubmissionEventSource {
        private final Deque<List<SubmissionFileEvent>> batches = new ArrayDeque<>();

        ScriptedEventSource(List<SubmissionFileEvent> firstBatch) {
            batches.add(firstBatch);
        }

        @Override
        public List<SubmissionFileEvent> pollEvents(Duration timeout) {
            List<SubmissionFileEvent> next = batches.pollFirst();
            return next == null ? List.of() : next;
        }

        @Override
        public void close() {
        }
    }

    private static final class NoopSubmissionApiClient implements SubmissionApiClient {
        @Override
        public SubmissionUploadResponse uploadSubmission(
            CoursePath coursePath, String studentId, Path filePath, String sha256) {
            return new SubmissionUploadResponse("noop-id", null, studentId, filePath.getFileName().toString(), sha256);
        }

        @Override
        public List<SubmissionSummaryResponse> listSubmissions(CoursePath coursePath) {
            return List.of();
        }

        @Override
        public DownloadedFile downloadSubmission(String submissionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteSubmission(String submissionId) {
        }
    }
}
