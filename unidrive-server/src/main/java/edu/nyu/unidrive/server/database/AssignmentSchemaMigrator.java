package edu.nyu.unidrive.server.database;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class AssignmentSchemaMigrator implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public AssignmentSchemaMigrator(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    public void migrate() {
        if (!tableExists("assignments")) {
            return;
        }

        if (!columnExists("assignments", "file_name")) {
            transactionTemplate.executeWithoutResult(status -> rebuildLegacyAssignmentsTable());
            return;
        }

        if (!columnExists("assignments", "deadline")) {
            jdbcTemplate.execute("ALTER TABLE assignments ADD COLUMN deadline INTEGER");
        }
    }

    private void rebuildLegacyAssignmentsTable() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, term, course, title, published_at, file_path, hash FROM assignments"
        );

        jdbcTemplate.execute("DROP TABLE IF EXISTS assignments_migration");
        jdbcTemplate.execute("""
            CREATE TABLE assignments_migration (
                id TEXT NOT NULL,
                file_name TEXT NOT NULL,
                term TEXT,
                course TEXT,
                title TEXT,
                deadline INTEGER,
                published_at INTEGER,
                file_path TEXT,
                hash TEXT,
                PRIMARY KEY (id, file_name)
            )
            """);

        for (Map<String, Object> row : rows) {
            String assignmentId = asString(row.get("id"));
            String filePath = asString(row.get("file_path"));
            jdbcTemplate.update(
                """
                    INSERT OR REPLACE INTO assignments_migration
                    (id, file_name, term, course, title, deadline, published_at, file_path, hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                assignmentId,
                deriveFileName(filePath, assignmentId),
                row.get("term"),
                row.get("course"),
                row.get("title"),
                null,
                row.get("published_at"),
                filePath,
                row.get("hash")
            );
        }

        jdbcTemplate.execute("DROP TABLE assignments");
        jdbcTemplate.execute("ALTER TABLE assignments_migration RENAME TO assignments");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            Integer.class,
            tableName
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        return jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")").stream()
            .map(row -> asString(row.get("name")))
            .anyMatch(columnName::equals);
    }

    private String deriveFileName(String filePath, String fallback) {
        if (filePath != null && !filePath.isBlank()) {
            Path fileName = Path.of(filePath).getFileName();
            if (fileName != null && !fileName.toString().isBlank()) {
                return fileName.toString();
            }
        }
        return fallback;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
