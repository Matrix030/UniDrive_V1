package edu.nyu.unidrive.server.repository;

import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbcTemplate;

    public FeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(String feedbackId, String submissionId, String filePath, String sha256, long returnedAt) {
        jdbcTemplate.update(
            "INSERT INTO feedback (id, submission_id, file_path, hash, returned_at) VALUES (?, ?, ?, ?, ?)",
            feedbackId,
            submissionId,
            filePath,
            sha256,
            returnedAt
        );
    }

    public List<FeedbackSummaryResponse> findByStudentId(String studentId) {
        return jdbcTemplate.query(
            """
            SELECT f.id, f.submission_id, s.term, s.course, s.assignment_id, s.student_id, f.file_path, f.hash
            FROM feedback f
            JOIN submissions s ON s.id = f.submission_id
            WHERE s.student_id = ?
            ORDER BY f.returned_at DESC
            """,
            (resultSet, rowNum) -> new FeedbackSummaryResponse(
                resultSet.getString("id"),
                resultSet.getString("submission_id"),
                resultSet.getString("term"),
                resultSet.getString("course"),
                resultSet.getString("assignment_id"),
                resultSet.getString("student_id"),
                Path.of(resultSet.getString("file_path")).getFileName().toString().substring(resultSet.getString("id").length() + 1),
                resultSet.getString("hash")
            ),
            studentId
        );
    }

    public Optional<StoredFeedback> findStoredFeedbackById(String feedbackId) {
        List<StoredFeedback> feedbackRows = jdbcTemplate.query(
            """
            SELECT f.id, f.submission_id, s.student_id, f.file_path, f.hash
            FROM feedback f
            JOIN submissions s ON s.id = f.submission_id
            WHERE f.id = ?
            """,
            (resultSet, rowNum) -> new StoredFeedback(
                resultSet.getString("id"),
                resultSet.getString("submission_id"),
                resultSet.getString("student_id"),
                resultSet.getString("file_path"),
                resultSet.getString("hash")
            ),
            feedbackId
        );
        return feedbackRows.stream().findFirst();
    }

    public record StoredFeedback(String id, String submissionId, String studentId, String filePath, String sha256) {
        public String originalFileName() {
            String storedFileName = Path.of(filePath).getFileName().toString();
            return storedFileName.substring(id.length() + 1);
        }
    }
}
