package edu.nyu.unidrive.server.service;

import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.server.repository.FeedbackRepository;
import edu.nyu.unidrive.server.repository.FeedbackRepository.StoredFeedback;
import edu.nyu.unidrive.server.repository.SubmissionRepository;
import edu.nyu.unidrive.server.repository.SubmissionRepository.StoredSubmissionDetails;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FeedbackService {

    private final Path storageRoot;
    private final SubmissionRepository submissionRepository;
    private final FeedbackRepository feedbackRepository;

    public FeedbackService(
        @Value("${unidrive.storage.root:unidrive-server/target/storage}") String storageRoot,
        SubmissionRepository submissionRepository,
        FeedbackRepository feedbackRepository
    ) {
        this.storageRoot = Path.of(storageRoot);
        this.submissionRepository = submissionRepository;
        this.feedbackRepository = feedbackRepository;
    }

    public FeedbackSummaryResponse uploadFeedback(String submissionId, MultipartFile file) throws IOException {
        StoredSubmissionDetails submission = submissionRepository.findSubmissionDetailsById(submissionId)
            .orElseThrow(SubmissionNotFoundException::new);

        byte[] content = file.getBytes();
        String sha256 = FileHasher.sha256Hex(content);
        String feedbackId = UUID.randomUUID().toString();
        String fileName = sanitizeFileName(file.getOriginalFilename());
        Path destination = storageRoot.resolve("feedback").resolve(feedbackId + "-" + fileName);

        AtomicFileWriter.write(destination, content);
        feedbackRepository.save(feedbackId, submissionId, destination.toString(), sha256, System.currentTimeMillis());

        return new FeedbackSummaryResponse(
            feedbackId,
            submissionId,
            submission.term(),
            submission.course(),
            submission.assignmentId(),
            submission.studentId(),
            fileName,
            sha256
        );
    }

    public List<FeedbackSummaryResponse> listFeedback(String studentId) {
        return feedbackRepository.findByStudentId(studentId);
    }

    public DownloadedFeedback loadFeedback(String feedbackId) throws IOException {
        StoredFeedback feedback = feedbackRepository.findStoredFeedbackById(feedbackId)
            .orElseThrow(FeedbackNotFoundException::new);
        return new DownloadedFeedback(feedback.originalFileName(), Files.readAllBytes(Path.of(feedback.filePath())));
    }

    private String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "feedback.bin";
        }
        Path fileNamePath = Path.of(originalFileName).getFileName();
        return fileNamePath == null ? "feedback.bin" : fileNamePath.toString();
    }

    public static final class SubmissionNotFoundException extends RuntimeException {
    }

    public static final class FeedbackNotFoundException extends RuntimeException {
    }

    public record DownloadedFeedback(String fileName, byte[] content) {
    }
}
