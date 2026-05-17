package edu.nyu.unidrive.server.repository;

import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import edu.nyu.unidrive.server.database.VersionCounter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbcTemplate;
    private final VersionCounter versionCounter;

    public FeedbackRepository(JdbcTemplate jdbcTemplate, VersionCounter versionCounter) {
        this.jdbcTemplate = jdbcTemplate;
        this.versionCounter = versionCounter;
        initializeSchema();
    }

    public void save(String feedbackId, String submissionId, String fileName, String filePath, String sha256, long returnedAt) {
        long version = versionCounter.allocate(VersionCounter.TABLE_FEEDBACK);
        jdbcTemplate.update(
            """
            INSERT INTO feedback (id, submission_id, file_name, file_path, hash, returned_at,
                                  version, deleted, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL)
            ON CONFLICT(id) DO UPDATE SET
                submission_id = excluded.submission_id,
                file_name = excluded.file_name,
                file_path = excluded.file_path,
                hash = excluded.hash,
                returned_at = excluded.returned_at,
                version = excluded.version,
                deleted = 0,
                deleted_at = NULL
            """,
            feedbackId, submissionId, fileName, filePath, sha256, returnedAt, version
        );
    }

    public List<FeedbackSummaryResponse> findByStudentId(String studentId) {
        return jdbcTemplate.query(
            """
            SELECT f.id, f.submission_id, s.term, s.course, s.assignment_id, s.student_id, f.file_name, f.file_path, f.hash
            FROM feedback f
            JOIN submissions s ON s.id = f.submission_id
            WHERE s.student_id = ? AND f.deleted = 0 AND s.deleted = 0
            ORDER BY f.returned_at DESC
            """,
            (resultSet, rowNum) -> new FeedbackSummaryResponse(
                resultSet.getString("id"),
                resultSet.getString("submission_id"),
                resultSet.getString("term"),
                resultSet.getString("course"),
                resultSet.getString("assignment_id"),
                resultSet.getString("student_id"),
                originalFileName(
                    resultSet.getString("id"),
                    resultSet.getString("file_name"),
                    resultSet.getString("file_path")
                ),
                resultSet.getString("hash")
            ),
            studentId
        );
    }

    public Optional<StoredFeedback> findStoredFeedbackById(String feedbackId) {
        List<StoredFeedback> feedbackRows = jdbcTemplate.query(
            """
            SELECT f.id, f.submission_id, s.student_id, f.file_name, f.file_path, f.hash
            FROM feedback f
            JOIN submissions s ON s.id = f.submission_id
            WHERE f.id = ? AND f.deleted = 0
            """,
            (resultSet, rowNum) -> new StoredFeedback(
                resultSet.getString("id"),
                resultSet.getString("submission_id"),
                resultSet.getString("student_id"),
                resultSet.getString("file_name"),
                resultSet.getString("file_path"),
                resultSet.getString("hash")
            ),
            feedbackId
        );
        return feedbackRows.stream().findFirst();
    }

    public Optional<StoredFeedback> findStoredFeedbackBySubmissionAndFileName(String submissionId, String fileName) {
        List<StoredFeedback> feedbackRows = jdbcTemplate.query(
            """
            SELECT f.id, f.submission_id, s.student_id, f.file_name, f.file_path, f.hash
            FROM feedback f
            JOIN submissions s ON s.id = f.submission_id
            WHERE f.submission_id = ? AND f.file_name = ? AND f.deleted = 0
            ORDER BY f.returned_at DESC
            LIMIT 1
            """,
            (resultSet, rowNum) -> new StoredFeedback(
                resultSet.getString("id"),
                resultSet.getString("submission_id"),
                resultSet.getString("student_id"),
                resultSet.getString("file_name"),
                resultSet.getString("file_path"),
                resultSet.getString("hash")
            ),
            submissionId,
            fileName
        );
        return feedbackRows.stream().findFirst();
    }

    public boolean deleteById(String feedbackId) {
        long version = versionCounter.allocate(VersionCounter.TABLE_FEEDBACK);
        int updated = jdbcTemplate.update(
            "UPDATE feedback SET deleted = 1, deleted_at = ?, version = ? WHERE id = ? AND deleted = 0",
            System.currentTimeMillis(), version, feedbackId);
        return updated > 0;
    }

    private void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS feedback (
                id TEXT PRIMARY KEY,
                submission_id TEXT,
                file_name TEXT,
                file_path TEXT,
                hash TEXT,
                returned_at INTEGER
            )
            """);
        if (!hasColumn("feedback", "file_name")) {
            jdbcTemplate.execute("ALTER TABLE feedback ADD COLUMN file_name TEXT");
        }
    }

    private boolean hasColumn(String tableName, String columnName) {
        return jdbcTemplate.query(
            "PRAGMA table_info(" + tableName + ")",
            (resultSet, rowNum) -> resultSet.getString("name")
        ).stream().anyMatch(columnName::equalsIgnoreCase);
    }

    private String originalFileName(String feedbackId, String fileName, String filePath) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        String storedFileName = Path.of(filePath).getFileName().toString();
        return storedFileName.substring(feedbackId.length() + 1);
    }

    public record StoredFeedback(String id, String submissionId, String studentId, String fileName, String filePath, String sha256) {
        public String originalFileName() {
            if (fileName != null && !fileName.isBlank()) {
                return fileName;
            }
            String storedFileName = Path.of(filePath).getFileName().toString();
            return storedFileName.substring(id.length() + 1);
        }
    }
}
