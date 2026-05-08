package edu.nyu.unidrive.server.service;

import edu.nyu.unidrive.common.dto.AssignmentSummaryResponse;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.server.repository.AssignmentRepository;
import edu.nyu.unidrive.server.repository.AssignmentRepository.StoredAssignment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AssignmentService {

    private final Path storageRoot;
    private final AssignmentRepository assignmentRepository;

    public AssignmentService(
        @Value("${unidrive.storage.root:unidrive-server/target/storage}") String storageRoot,
        AssignmentRepository assignmentRepository
    ) {
        this.storageRoot = Path.of(storageRoot);
        this.assignmentRepository = assignmentRepository;
    }

    public AssignmentSummaryResponse publishAssignment(
        String term,
        String course,
        String assignmentId,
        String title,
        String deadline,
        MultipartFile file
    ) throws IOException {
        byte[] content = file.getBytes();
        String sha256 = FileHasher.sha256Hex(content);
        String fileName = sanitizeFileName(file.getOriginalFilename());
        long deadlineMillis = parseDeadline(deadline);
        Path destination = storageRoot
            .resolve(term)
            .resolve(course)
            .resolve(assignmentId)
            .resolve("publish")
            .resolve(fileName);

        AtomicFileWriter.write(destination, content);
        assignmentRepository.save(
            assignmentId,
            fileName,
            term,
            course,
            title,
            deadlineMillis,
            System.currentTimeMillis(),
            destination.toString(),
            sha256
        );

        return new AssignmentSummaryResponse(assignmentId, term, course, title, fileName, sha256, Instant.ofEpochMilli(deadlineMillis).toString());
    }

    public List<AssignmentSummaryResponse> listAssignments(String term, String course) {
        return assignmentRepository.findByTermAndCourse(term, course);
    }

    public DownloadedAssignment loadAssignment(String assignmentId, String fileName) throws IOException {
        StoredAssignment assignment = assignmentRepository.findStoredAssignmentByIdAndFileName(assignmentId, fileName)
            .orElseThrow(AssignmentNotFoundException::new);
        return new DownloadedAssignment(assignment.originalFileName(), Files.readAllBytes(Path.of(assignment.filePath())));
    }

    public void deleteAssignment(String assignmentId, String fileName) throws IOException {
        StoredAssignment assignment = assignmentRepository.findStoredAssignmentByIdAndFileName(assignmentId, fileName)
            .orElseThrow(AssignmentNotFoundException::new);
        Files.deleteIfExists(Path.of(assignment.filePath()));
        assignmentRepository.deleteByIdAndFileName(assignmentId, fileName);
    }

    private String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "assignment.bin";
        }
        Path fileNamePath = Path.of(originalFileName).getFileName();
        return fileNamePath == null ? "assignment.bin" : fileNamePath.toString();
    }

    private long parseDeadline(String deadline) {
        if (deadline == null || deadline.isBlank()) {
            throw new InvalidDeadlineException();
        }
        try {
            return Instant.parse(deadline.trim()).toEpochMilli();
        } catch (DateTimeParseException exception) {
            throw new InvalidDeadlineException();
        }
    }

    public static final class AssignmentNotFoundException extends RuntimeException {
    }

    public static final class InvalidDeadlineException extends RuntimeException {
    }

    public record DownloadedAssignment(String fileName, byte[] content) {
    }
}
