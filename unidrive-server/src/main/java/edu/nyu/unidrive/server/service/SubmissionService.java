package edu.nyu.unidrive.server.service;

import edu.nyu.unidrive.common.dto.SubmissionSummaryResponse;
import edu.nyu.unidrive.common.dto.SubmissionUploadResponse;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.server.repository.SubmissionRepository.StoredSubmission;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.server.repository.AssignmentRepository;
import edu.nyu.unidrive.server.repository.SubmissionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SubmissionService {

    private final Path storageRoot;
    private final SubmissionRepository submissionRepository;
    private final AssignmentRepository assignmentRepository;

    public SubmissionService(
        @Value("${unidrive.storage.root:unidrive-server/target/storage}") String storageRoot,
        SubmissionRepository submissionRepository,
        AssignmentRepository assignmentRepository
    ) {
        this.storageRoot = Path.of(storageRoot);
        this.submissionRepository = submissionRepository;
        this.assignmentRepository = assignmentRepository;
    }

    public SubmissionUploadResponse storeSubmission(
        String term,
        String course,
        String assignmentId,
        String studentId,
        String providedSha256,
        MultipartFile file
    ) throws IOException {
        ensureSubmissionWindowOpen(term, course, assignmentId);

        byte[] content = file.getBytes();
        String computedSha256 = FileHasher.sha256Hex(content);

        if (!computedSha256.equals(providedSha256)) {
            throw new HashMismatchException();
        }

        String submissionId = UUID.randomUUID().toString();
        String fileName = sanitizeFileName(file.getOriginalFilename());
        deleteExistingSubmissionsForFile(term, course, assignmentId, studentId, fileName);
        Path destination = storageRoot
            .resolve(term)
            .resolve(course)
            .resolve(assignmentId)
            .resolve("submissions")
            .resolve("student_" + studentId)
            .resolve(submissionId + "-" + fileName);

        AtomicFileWriter.write(destination, content);
        submissionRepository.save(
            submissionId,
            term,
            course,
            assignmentId,
            studentId,
            destination.toString(),
            computedSha256,
            System.currentTimeMillis(),
            SyncStatus.SYNCED.name()
        );

        return new SubmissionUploadResponse(
            submissionId,
            term,
            course,
            assignmentId,
            studentId,
            fileName,
            computedSha256
        );
    }

    public List<SubmissionSummaryResponse> listSubmissions(
        String term,
        String course,
        String assignmentId,
        String studentId
    ) {
        Map<String, SubmissionSummaryResponse> latestByStudentFile = new LinkedHashMap<>();
        for (SubmissionSummaryResponse submission : submissionRepository.findByAssignment(term, course, assignmentId, studentId)) {
            latestByStudentFile.putIfAbsent(submission.getStudentId() + "/" + submission.getFileName(), submission);
        }
        return List.copyOf(latestByStudentFile.values());
    }

    public DownloadedSubmission loadSubmission(String submissionId) throws IOException {
        StoredSubmission storedSubmission = submissionRepository.findStoredSubmissionById(submissionId)
            .orElseThrow(SubmissionNotFoundException::new);

        Path filePath = Path.of(storedSubmission.filePath());
        byte[] content = Files.readAllBytes(filePath);
        return new DownloadedSubmission(storedSubmission.originalFileName(), content);
    }

    public void deleteSubmission(String submissionId) throws IOException {
        StoredSubmission storedSubmission = submissionRepository.findStoredSubmissionById(submissionId)
            .orElseThrow(SubmissionNotFoundException::new);
        submissionRepository.findSubmissionDetailsById(submissionId)
            .ifPresent(details -> ensureDeletionWindowOpen(details.term(), details.course(), details.assignmentId()));
        Files.deleteIfExists(Path.of(storedSubmission.filePath()));
        submissionRepository.deleteById(submissionId);
    }

    private void ensureSubmissionWindowOpen(String term, String course, String assignmentId) {
        AssignmentRepository.AssignmentDeadline assignmentDeadline = assignmentRepository
            .findDeadlineByAssignment(term, course, assignmentId)
            .orElseThrow(AssignmentNotFoundException::new);
        if (assignmentDeadline.deadlineMillis() != null && System.currentTimeMillis() > assignmentDeadline.deadlineMillis()) {
            throw new DeadlinePassedException();
        }
    }

    private void ensureDeletionWindowOpen(String term, String course, String assignmentId) {
        assignmentRepository.findDeadlineByAssignment(term, course, assignmentId)
            .map(AssignmentRepository.AssignmentDeadline::deadlineMillis)
            .filter(deadline -> System.currentTimeMillis() > deadline)
            .ifPresent(deadline -> {
                throw new DeadlinePassedException();
            });
    }

    private void deleteExistingSubmissionsForFile(
        String term,
        String course,
        String assignmentId,
        String studentId,
        String fileName
    ) throws IOException {
        for (SubmissionSummaryResponse existing : submissionRepository.findByAssignment(term, course, assignmentId, studentId)) {
            if (fileName.equals(existing.getFileName())) {
                deleteSubmission(existing.getSubmissionId());
            }
        }
    }

    private String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "upload.bin";
        }

        Path fileNamePath = Path.of(originalFileName).getFileName();
        return fileNamePath == null ? "upload.bin" : fileNamePath.toString();
    }

    public static final class HashMismatchException extends RuntimeException {
    }

    public static final class SubmissionNotFoundException extends RuntimeException {
    }

    public static final class AssignmentNotFoundException extends RuntimeException {
    }

    public static final class DeadlinePassedException extends RuntimeException {
    }

    public record DownloadedSubmission(String fileName, byte[] content) {
    }
}
