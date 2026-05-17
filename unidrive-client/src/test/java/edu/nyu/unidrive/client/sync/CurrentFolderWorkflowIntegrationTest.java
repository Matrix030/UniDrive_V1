package edu.nyu.unidrive.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.nyu.unidrive.client.net.AssignmentApiClient;
import edu.nyu.unidrive.client.net.DownloadedFile;
import edu.nyu.unidrive.client.net.FeedbackApiClient;
import edu.nyu.unidrive.client.net.SubmissionApiClient;
import edu.nyu.unidrive.client.storage.AssignmentDeadlineStore;
import edu.nyu.unidrive.client.storage.AssignmentSlot;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.client.storage.SyncStateRepository;
import edu.nyu.unidrive.client.storage.WorkspaceLayout;
import edu.nyu.unidrive.common.dto.AssignmentSummaryResponse;
import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import edu.nyu.unidrive.common.dto.SubmissionSummaryResponse;
import edu.nyu.unidrive.common.dto.SubmissionUploadResponse;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.MockCourseRegistry;
import edu.nyu.unidrive.common.workspace.WorkspaceRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentFolderWorkflowIntegrationTest {

    @Test
    void instructorPublishStudentSubmitAndInstructorCollectUsesCurrentFolderLayout(@TempDir Path tempDir) throws Exception {
        Path instructorRoot = tempDir.resolve("instructor");
        Path studentRoot = tempDir.resolve("student");
        Path instructorDb = tempDir.resolve("instructor.db");
        Path studentDb = tempDir.resolve("student.db");
        CurrentFolderServer server = new CurrentFolderServer();
        CoursePath coursePath = new CoursePath(MockCourseRegistry.CURRENT_TERM, "daa", "hashing");

        AssignmentSlot instructorSlot = WorkspaceLayout.ensureAssignmentSlot(instructorRoot, coursePath, WorkspaceRole.INSTRUCTOR);
        AssignmentDeadlineStore.writeDeadline(instructorRoot, coursePath, "2099-12-31T23:59:00Z");
        Path instructorSpec = instructorSlot.publishDir().resolve("hashing_assignment.md");
        Files.writeString(instructorSpec, "Implement a hash table.");

        SyncStateRepository instructorSyncRepository = new SyncStateRepository(instructorDb);
        PublishDirectoryWatcher publishWatcher = new PublishDirectoryWatcher(instructorRoot);
        try {
            PublishSyncService publishSync = new PublishSyncService(
                publishWatcher,
                new PublishUploadService(server, instructorRoot),
                instructorSyncRepository,
                instructorRoot,
                Duration.ZERO
            );
            publishSync.runStartupReconcile();
            publishSync.processOnce();
        } finally {
            publishWatcher.close();
        }

        Path studentSpec = studentRoot.resolve("fall2026/daa/hashing/files/hashing_assignment.md");
        ReceivedStateRepository studentReceivedRepository = new ReceivedStateRepository(studentDb);
        int downloadedAssignments = new AssignmentSyncService(server, studentReceivedRepository)
            .syncAssignmentsForCourse("fall2026", "daa", studentRoot);

        WorkspaceLayout.ensureAssignmentSlot(studentRoot, coursePath, WorkspaceRole.STUDENT);
        Path studentSolution = studentRoot.resolve("fall2026/daa/hashing/submission/Solution.java");
        Files.writeString(studentSolution, "class Solution {}");

        SyncStateRepository studentSyncRepository = new SyncStateRepository(studentDb);
        SyncStatus uploadStatus = new SubmissionUploadService(studentSyncRepository, server, studentRoot)
            .uploadPendingSubmission("rvg9395", studentSolution);

        ReceivedStateRepository instructorReceivedRepository = new ReceivedStateRepository(instructorDb);
        new InstructorSubmissionPollingService(
            server,
            instructorRoot,
            new MockCourseRegistry(),
            instructorReceivedRepository,
            Duration.ZERO
        ).processOnce();

        Path instructorCopy = instructorRoot.resolve("fall2026/daa/hashing/submissions/student_rvg9395/Solution.java");
        Path instructorFeedback = instructorRoot.resolve("fall2026/daa/hashing/submissions/student_rvg9395/feedback/comments.txt");
        Files.writeString(instructorFeedback, "Good work. Add tests next time.");
        FeedbackDirectoryWatcher feedbackWatcher = new FeedbackDirectoryWatcher(instructorRoot);
        try {
            InstructorFeedbackSyncService feedbackSync = new InstructorFeedbackSyncService(
                feedbackWatcher,
                new FeedbackUploadService(instructorSyncRepository, server, server, instructorRoot),
                new FeedbackReconcileService(instructorSyncRepository),
                instructorSyncRepository,
                instructorRoot,
                Duration.ZERO
            );
            feedbackSync.runStartupReconcile();
            feedbackSync.processOnce();
        } finally {
            feedbackWatcher.close();
        }

        Path studentFeedback = studentRoot.resolve("fall2026/daa/hashing/feedback/comments.txt");
        new FeedbackSyncService(server, studentReceivedRepository).syncFeedback("rvg9395", studentRoot);

        assertEquals(1, downloadedAssignments);
        assertTrue(Files.exists(studentSpec));
        assertEquals("Implement a hash table.", Files.readString(studentSpec));
        assertEquals(SyncStatus.SYNCED, uploadStatus);
        assertEquals(SyncStatus.SYNCED, studentSyncRepository.findByLocalPath(studentSolution).orElseThrow().status());
        assertTrue(Files.exists(instructorCopy));
        assertEquals("class Solution {}", Files.readString(instructorCopy));
        assertEquals(SyncStatus.SYNCED, instructorReceivedRepository.findByLocalPath(instructorCopy).orElseThrow().status());
        assertTrue(Files.exists(studentFeedback));
        assertEquals("Good work. Add tests next time.", Files.readString(studentFeedback));

        Files.delete(instructorFeedback);
        FeedbackDirectoryWatcher deleteFeedbackWatcher = new FeedbackDirectoryWatcher(instructorRoot);
        try {
            new InstructorFeedbackSyncService(
                deleteFeedbackWatcher,
                new FeedbackUploadService(instructorSyncRepository, server, server, instructorRoot),
                new FeedbackReconcileService(instructorSyncRepository),
                instructorSyncRepository,
                instructorRoot,
                Duration.ZERO
            ).processOnce();
        } finally {
            deleteFeedbackWatcher.close();
        }
        new FeedbackSyncService(server, studentReceivedRepository).syncFeedback("rvg9395", studentRoot);
        assertTrue(Files.notExists(studentFeedback));
    }

    private static final class CurrentFolderServer implements AssignmentApiClient, SubmissionApiClient, FeedbackApiClient {

        private final Map<String, StoredAssignment> assignments = new LinkedHashMap<>();
        private final Map<String, StoredSubmission> submissions = new LinkedHashMap<>();
        private final Map<String, StoredFeedback> feedbackRows = new LinkedHashMap<>();
        private int nextSubmissionId = 1;
        private int nextFeedbackId = 1;

        @Override
        public List<AssignmentSummaryResponse> listAssignments(String term, String courseSlug) {
            return assignments.values().stream()
                .filter(assignment -> assignment.coursePath.term().equals(term))
                .filter(assignment -> assignment.coursePath.courseSlug().equals(courseSlug))
                .map(StoredAssignment::summary)
                .toList();
        }

        @Override
        public DownloadedFile downloadAssignment(String assignmentId, String fileName) {
            StoredAssignment assignment = assignments.get(assignmentId + "/" + fileName);
            return new DownloadedFile(fileName, assignment.content);
        }

        @Override
        public AssignmentSummaryResponse publishAssignment(CoursePath coursePath, String title, String deadline, Path file) throws IOException {
            byte[] content = Files.readAllBytes(file);
            String fileName = file.getFileName().toString();
            String sha256 = FileHasher.sha256Hex(content);
            AssignmentSummaryResponse summary = new AssignmentSummaryResponse(
                coursePath.assignmentId(),
                coursePath.term(),
                coursePath.courseSlug(),
                title,
                fileName,
                sha256,
                deadline
            );
            assignments.put(coursePath.assignmentId() + "/" + fileName, new StoredAssignment(coursePath, summary, content));
            return summary;
        }

        @Override
        public void deleteAssignment(String assignmentId, String fileName) {
            assignments.remove(assignmentId + "/" + fileName);
        }

        @Override
        public SubmissionUploadResponse uploadSubmission(CoursePath coursePath, String studentId, Path filePath, String sha256)
            throws IOException {
            byte[] content = Files.readAllBytes(filePath);
            String computedSha256 = FileHasher.sha256Hex(content);
            if (!computedSha256.equals(sha256)) {
                throw new IOException("hash mismatch");
            }
            String submissionId = "submission-" + nextSubmissionId++;
            String fileName = filePath.getFileName().toString();
            SubmissionSummaryResponse summary = new SubmissionSummaryResponse(
                submissionId,
                coursePath.term(),
                coursePath.courseSlug(),
                coursePath.assignmentId(),
                studentId,
                fileName,
                computedSha256,
                SyncStatus.SYNCED.name()
            );
            submissions.put(submissionId, new StoredSubmission(summary, content));
            return new SubmissionUploadResponse(
                submissionId,
                coursePath.term(),
                coursePath.courseSlug(),
                coursePath.assignmentId(),
                studentId,
                fileName,
                computedSha256
            );
        }

        @Override
        public List<SubmissionSummaryResponse> listSubmissions(CoursePath coursePath) {
            List<SubmissionSummaryResponse> summaries = new ArrayList<>();
            for (StoredSubmission submission : submissions.values()) {
                SubmissionSummaryResponse summary = submission.summary;
                if (summary.getTerm().equals(coursePath.term())
                    && summary.getCourse().equals(coursePath.courseSlug())
                    && summary.getAssignmentId().equals(coursePath.assignmentId())) {
                    summaries.add(summary);
                }
            }
            return summaries;
        }

        @Override
        public DownloadedFile downloadSubmission(String submissionId) {
            StoredSubmission submission = submissions.get(submissionId);
            return new DownloadedFile(submission.summary.getFileName(), submission.content);
        }

        @Override
        public void deleteSubmission(String submissionId) {
            submissions.remove(submissionId);
        }

        @Override
        public List<FeedbackSummaryResponse> listFeedback(String studentId) {
            List<FeedbackSummaryResponse> feedback = new ArrayList<>();
            for (StoredFeedback storedFeedback : feedbackRows.values()) {
                SubmissionSummaryResponse submission = submissions.get(storedFeedback.submissionId).summary;
                if (studentId.equals(submission.getStudentId())) {
                    feedback.add(storedFeedback.summary);
                }
            }
            return feedback;
        }

        @Override
        public DownloadedFile downloadFeedback(String feedbackId) {
            StoredFeedback feedback = feedbackRows.values().stream()
                .filter(row -> row.summary.getFeedbackId().equals(feedbackId))
                .findFirst()
                .orElseThrow();
            return new DownloadedFile(feedback.summary.getFileName(), feedback.content);
        }

        @Override
        public FeedbackSummaryResponse uploadFeedback(String submissionId, Path file) throws IOException {
            StoredSubmission submission = submissions.get(submissionId);
            byte[] content = Files.readAllBytes(file);
            String fileName = file.getFileName().toString();
            String key = submissionId + "/" + fileName;
            StoredFeedback existing = feedbackRows.get(key);
            String feedbackId = existing == null ? "feedback-" + nextFeedbackId++ : existing.summary.getFeedbackId();
            FeedbackSummaryResponse summary = new FeedbackSummaryResponse(
                feedbackId,
                submissionId,
                submission.summary.getTerm(),
                submission.summary.getCourse(),
                submission.summary.getAssignmentId(),
                submission.summary.getStudentId(),
                fileName,
                FileHasher.sha256Hex(content)
            );
            feedbackRows.put(key, new StoredFeedback(submissionId, summary, content));
            return summary;
        }

        @Override
        public void deleteFeedback(String feedbackId) {
            feedbackRows.entrySet().removeIf(entry -> entry.getValue().summary.getFeedbackId().equals(feedbackId));
        }

        private record StoredAssignment(CoursePath coursePath, AssignmentSummaryResponse summary, byte[] content) {
        }

        private record StoredSubmission(SubmissionSummaryResponse summary, byte[] content) {
        }

        private record StoredFeedback(String submissionId, FeedbackSummaryResponse summary, byte[] content) {
        }
    }
}
