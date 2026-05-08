package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.client.SyncServiceHandle;
import edu.nyu.unidrive.client.net.DownloadedFile;
import edu.nyu.unidrive.client.net.SubmissionApiClient;
import edu.nyu.unidrive.client.storage.ReceivedStateRecord;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.common.dto.SubmissionSummaryResponse;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.MockCourseRegistry;
import edu.nyu.unidrive.common.workspace.MockCourseRegistry.Course;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class InstructorSubmissionPollingService implements SyncServiceHandle {

    private final SubmissionApiClient submissionApiClient;
    private final Path workspaceRoot;
    private final MockCourseRegistry courseRegistry;
    private final ReceivedStateRepository receivedStateRepository;
    private final Duration pollInterval;
    private final Map<Path, String> latestSubmissionByFeedbackDirectory = new ConcurrentHashMap<>();
    private Thread workerThread;

    public InstructorSubmissionPollingService(
        SubmissionApiClient submissionApiClient,
        Path workspaceRoot,
        MockCourseRegistry courseRegistry,
        ReceivedStateRepository receivedStateRepository,
        Duration pollInterval
    ) {
        this.submissionApiClient = submissionApiClient;
        this.workspaceRoot = workspaceRoot;
        this.courseRegistry = courseRegistry;
        this.receivedStateRepository = receivedStateRepository;
        this.pollInterval = pollInterval;
    }

    @Override
    public synchronized void start() {
        if (workerThread != null) {
            return;
        }
        workerThread = new Thread(this::runLoop, "unidrive-instructor-submission-polling");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void processOnce() {
        try {
            for (Course course : courseRegistry.courses()) {
                Path courseDir = workspaceRoot.resolve(courseRegistry.currentTerm()).resolve(course.slug());
                if (!Files.isDirectory(courseDir)) {
                    continue;
                }
                try (Stream<Path> assignmentDirs = Files.list(courseDir)) {
                    assignmentDirs
                        .filter(Files::isDirectory)
                        .forEach(assignmentDir -> {
                            CoursePath coursePath = new CoursePath(
                                courseRegistry.currentTerm(),
                                course.slug(),
                                assignmentDir.getFileName().toString()
                            );
                            try {
                                pollAssignment(coursePath, assignmentDir);
                            } catch (IOException ignored) {
                            }
                        });
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan instructor workspace.", exception);
        }
    }

    private void pollAssignment(CoursePath coursePath, Path assignmentDir) throws IOException {
        Path submissionsDir = assignmentDir.resolve(CoursePath.INSTRUCTOR_SUBMISSIONS_DIR);
        Files.createDirectories(submissionsDir);
        Set<Path> expectedFiles = new HashSet<>();
        Set<String> seenStudentSubmissions = new HashSet<>();

        for (SubmissionSummaryResponse submission : submissionApiClient.listSubmissions(coursePath)) {
            Path studentDir = submissionsDir.resolve(CoursePath.STUDENT_PREFIX + submission.getStudentId());
            Files.createDirectories(studentDir);
            Path feedbackDir = studentDir.resolve(CoursePath.FEEDBACK_DIR);
            Files.createDirectories(feedbackDir);

            if (seenStudentSubmissions.add(submission.getStudentId())) {
                latestSubmissionByFeedbackDirectory.put(feedbackDir.toAbsolutePath().normalize(), submission.getSubmissionId());
            }

            Path destination = studentDir.resolve(submission.getFileName());
            expectedFiles.add(destination);
            if (Files.exists(destination) && FileHasher.sha256Hex(destination).equals(submission.getSha256())) {
                receivedStateRepository.save(new ReceivedStateRecord(
                    destination,
                    submission.getSubmissionId(),
                    submission.getSha256(),
                    SyncStatus.SYNCED,
                    System.currentTimeMillis(),
                    ReceivedReconcileService.SOURCE_INSTRUCTOR_SUBMISSIONS
                ));
                continue;
            }

            receivedStateRepository.save(new ReceivedStateRecord(
                destination,
                submission.getSubmissionId(),
                submission.getSha256(),
                SyncStatus.PENDING,
                0L,
                ReceivedReconcileService.SOURCE_INSTRUCTOR_SUBMISSIONS
            ));

            DownloadedFile download = submissionApiClient.downloadSubmission(submission.getSubmissionId());
            Files.write(destination, download.content());

            receivedStateRepository.save(new ReceivedStateRecord(
                destination,
                submission.getSubmissionId(),
                submission.getSha256(),
                SyncStatus.SYNCED,
                System.currentTimeMillis(),
                ReceivedReconcileService.SOURCE_INSTRUCTOR_SUBMISSIONS
            ));
        }
        removeStaleSubmissionFiles(submissionsDir, expectedFiles);
    }

    private void removeStaleSubmissionFiles(Path submissionsDir, Set<Path> expectedFiles) throws IOException {
        try (Stream<Path> files = Files.walk(submissionsDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (isFeedbackFile(submissionsDir, file)) {
                    continue;
                }
                if (!expectedFiles.contains(file)) {
                    Files.delete(file);
                    receivedStateRepository.deleteByLocalPath(file);
                }
            }
        }
    }

    private boolean isFeedbackFile(Path submissionsDir, Path file) {
        Path relative = submissionsDir.relativize(file);
        for (Path name : relative) {
            if (CoursePath.FEEDBACK_DIR.equals(name.toString())) {
                return true;
            }
        }
        return false;
    }

    public Map<Path, String> latestSubmissionByFeedbackDirectory() {
        return latestSubmissionByFeedbackDirectory;
    }

    @Override
    public synchronized void close() {
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    private void runLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                processOnce();
            } catch (RuntimeException ignored) {
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
