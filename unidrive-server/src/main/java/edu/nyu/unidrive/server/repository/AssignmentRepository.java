package edu.nyu.unidrive.server.repository;

import edu.nyu.unidrive.common.dto.AssignmentSummaryResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AssignmentRepository {

    private static final RowMapper<AssignmentSummaryResponse> SUMMARY_ROW_MAPPER = (resultSet, rowNum) ->
        new AssignmentSummaryResponse(
            resultSet.getString("id"),
            resultSet.getString("term"),
            resultSet.getString("course"),
            resultSet.getString("title"),
            resultSet.getString("file_name"),
            resultSet.getString("hash"),
            deadlineText(resultSet)
        );

    private final JdbcTemplate jdbcTemplate;

    public AssignmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(
        String assignmentId,
        String fileName,
        String term,
        String course,
        String title,
        long deadline,
        long publishedAt,
        String filePath,
        String sha256
    ) {
        jdbcTemplate.update(
            """
            INSERT OR REPLACE INTO assignments (id, file_name, term, course, title, deadline, published_at, file_path, hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            assignmentId,
            fileName,
            term,
            course,
            title,
            deadline,
            publishedAt,
            filePath,
            sha256
        );
        jdbcTemplate.update(
            "UPDATE assignments SET deadline = ? WHERE id = ? AND term = ? AND course = ?",
            deadline,
            assignmentId,
            term,
            course
        );
    }

    public List<AssignmentSummaryResponse> findByTermAndCourse(String term, String course) {
        return jdbcTemplate.query(
            "SELECT id, file_name, term, course, title, file_path, hash, deadline FROM assignments "
                + "WHERE term = ? AND course = ? ORDER BY published_at DESC",
            SUMMARY_ROW_MAPPER,
            term,
            course
        );
    }

    public List<AssignmentSummaryResponse> findAll() {
        return jdbcTemplate.query(
            "SELECT id, file_name, term, course, title, file_path, hash, deadline FROM assignments ORDER BY published_at DESC",
            SUMMARY_ROW_MAPPER
        );
    }

    public Optional<AssignmentDeadline> findDeadlineByAssignment(String term, String course, String assignmentId) {
        List<AssignmentDeadline> deadlines = jdbcTemplate.query(
            """
            SELECT deadline FROM assignments
            WHERE term = ? AND course = ? AND id = ?
            ORDER BY published_at DESC LIMIT 1
            """,
            (resultSet, rowNum) -> new AssignmentDeadline(deadlineMillis(resultSet)),
            term,
            course,
            assignmentId
        );
        return deadlines.stream().findFirst();
    }

    public Optional<StoredAssignment> findStoredAssignmentByIdAndFileName(String assignmentId, String fileName) {
        List<StoredAssignment> assignments = jdbcTemplate.query(
            "SELECT id, file_name, term, course, title, file_path, hash, deadline FROM assignments WHERE id = ? AND file_name = ?",
            (resultSet, rowNum) -> new StoredAssignment(
                resultSet.getString("id"),
                resultSet.getString("file_name"),
                resultSet.getString("term"),
                resultSet.getString("course"),
                resultSet.getString("title"),
                resultSet.getString("file_path"),
                resultSet.getString("hash"),
                deadlineMillis(resultSet)
            ),
            assignmentId,
            fileName
        );
        return assignments.stream().findFirst();
    }

    public void deleteByIdAndFileName(String assignmentId, String fileName) {
        jdbcTemplate.update("DELETE FROM assignments WHERE id = ? AND file_name = ?", assignmentId, fileName);
    }

    private static String deadlineText(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        Long deadline = deadlineMillis(resultSet);
        return deadline == null ? null : Instant.ofEpochMilli(deadline).toString();
    }

    private static Long deadlineMillis(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        long deadline = resultSet.getLong("deadline");
        return resultSet.wasNull() ? null : deadline;
    }

    public record AssignmentDeadline(Long deadlineMillis) {
    }

    public record StoredAssignment(
        String id,
        String fileName,
        String term,
        String course,
        String title,
        String filePath,
        String sha256,
        Long deadlineMillis
    ) {
        public String originalFileName() {
            return fileName;
        }
    }
}
