package edu.nyu.unidrive.server.repository;

import edu.nyu.unidrive.common.dto.AssignmentSummaryResponse;
import edu.nyu.unidrive.server.database.VersionCounter;
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
    private final VersionCounter versionCounter;

    public AssignmentRepository(JdbcTemplate jdbcTemplate, VersionCounter versionCounter) {
        this.jdbcTemplate = jdbcTemplate;
        this.versionCounter = versionCounter;
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
        long version = versionCounter.allocate(VersionCounter.TABLE_ASSIGNMENTS);
        jdbcTemplate.update(
            """
            INSERT INTO assignments
                (id, file_name, term, course, title, deadline, published_at, file_path, hash,
                 version, deleted, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL)
            ON CONFLICT(id, file_name) DO UPDATE SET
                term = excluded.term,
                course = excluded.course,
                title = excluded.title,
                deadline = excluded.deadline,
                published_at = excluded.published_at,
                file_path = excluded.file_path,
                hash = excluded.hash,
                version = excluded.version,
                deleted = 0,
                deleted_at = NULL
            """,
            assignmentId, fileName, term, course, title, deadline, publishedAt, filePath, sha256, version
        );
    }

    public List<AssignmentSummaryResponse> findByTermAndCourse(String term, String course) {
        return jdbcTemplate.query(
            "SELECT id, file_name, term, course, title, file_path, hash, deadline FROM assignments "
                + "WHERE term = ? AND course = ? AND deleted = 0 ORDER BY published_at DESC",
            SUMMARY_ROW_MAPPER,
            term,
            course
        );
    }

    public List<AssignmentSummaryResponse> findAll() {
        return jdbcTemplate.query(
            "SELECT id, file_name, term, course, title, file_path, hash, deadline FROM assignments "
                + "WHERE deleted = 0 ORDER BY published_at DESC",
            SUMMARY_ROW_MAPPER
        );
    }

    public Optional<AssignmentDeadline> findDeadlineByAssignment(String term, String course, String assignmentId) {
        List<AssignmentDeadline> deadlines = jdbcTemplate.query(
            """
            SELECT deadline FROM assignments
            WHERE term = ? AND course = ? AND id = ? AND deleted = 0
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
            "SELECT id, file_name, term, course, title, file_path, hash, deadline FROM assignments "
                + "WHERE id = ? AND file_name = ? AND deleted = 0",
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
        long version = versionCounter.allocate(VersionCounter.TABLE_ASSIGNMENTS);
        jdbcTemplate.update(
            "UPDATE assignments SET deleted = 1, deleted_at = ?, version = ? "
                + "WHERE id = ? AND file_name = ?",
            System.currentTimeMillis(), version, assignmentId, fileName);
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
