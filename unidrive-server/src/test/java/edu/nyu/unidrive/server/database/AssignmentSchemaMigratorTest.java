package edu.nyu.unidrive.server.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.nyu.unidrive.server.repository.AssignmentRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AssignmentSchemaMigratorTest {

    @Test
    void migrateRebuildsLegacyAssignmentsTableWithFileName(@TempDir Path tempDir) {
        JdbcTemplate jdbcTemplate = jdbcTemplate(tempDir.resolve("legacy.db"));
        jdbcTemplate.execute("""
            CREATE TABLE assignments (
                id TEXT PRIMARY KEY,
                term TEXT,
                course TEXT,
                title TEXT,
                published_at INTEGER,
                file_path TEXT,
                hash TEXT
            )
            """);
        jdbcTemplate.update(
            "INSERT INTO assignments (id, term, course, title, published_at, file_path, hash) VALUES (?, ?, ?, ?, ?, ?, ?)",
            "hw1",
            "fall2026",
            "daa",
            "Assignment 1",
            123L,
            tempDir.resolve("fall2026/daa/hw1/publish/spec.md").toString(),
            "abc123"
        );

        new AssignmentSchemaMigrator(
            jdbcTemplate,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        ).migrate();

        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(assignments)");
        assertTrue(columns.stream().anyMatch(column -> "file_name".equals(column.get("name"))));
        assertTrue(columns.stream().anyMatch(column -> "deadline".equals(column.get("name"))));
        assertEquals("spec.md", jdbcTemplate.queryForObject("SELECT file_name FROM assignments WHERE id = ?", String.class, "hw1"));

        AssignmentRepository repository = new AssignmentRepository(jdbcTemplate);
        assertEquals("spec.md", repository.findStoredAssignmentByIdAndFileName("hw1", "spec.md").orElseThrow().fileName());
    }

    @Test
    void migrateLeavesCurrentAssignmentsTableUntouched(@TempDir Path tempDir) {
        JdbcTemplate jdbcTemplate = jdbcTemplate(tempDir.resolve("current.db"));
        jdbcTemplate.execute("""
            CREATE TABLE assignments (
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
        jdbcTemplate.update(
            "INSERT INTO assignments (id, file_name, term, course, title, deadline, published_at, file_path, hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "hw1",
            "starter.zip",
            "fall2026",
            "daa",
            "Assignment 1",
            4102444740000L,
            123L,
            tempDir.resolve("starter.zip").toString(),
            "def456"
        );

        new AssignmentSchemaMigrator(
            jdbcTemplate,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        ).migrate();

        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assignments", Integer.class));
        assertEquals("starter.zip", jdbcTemplate.queryForObject("SELECT file_name FROM assignments WHERE id = ?", String.class, "hw1"));
        assertEquals(4102444740000L, jdbcTemplate.queryForObject("SELECT deadline FROM assignments WHERE id = ?", Long.class, "hw1"));
    }

    @Test
    void migrateAddsDeadlineToCurrentAssignmentsTable(@TempDir Path tempDir) {
        JdbcTemplate jdbcTemplate = jdbcTemplate(tempDir.resolve("missing-deadline.db"));
        jdbcTemplate.execute("""
            CREATE TABLE assignments (
                id TEXT NOT NULL,
                file_name TEXT NOT NULL,
                term TEXT,
                course TEXT,
                title TEXT,
                published_at INTEGER,
                file_path TEXT,
                hash TEXT,
                PRIMARY KEY (id, file_name)
            )
            """);

        new AssignmentSchemaMigrator(
            jdbcTemplate,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        ).migrate();

        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(assignments)");
        assertTrue(columns.stream().anyMatch(column -> "deadline".equals(column.get("name"))));
    }

    private JdbcTemplate jdbcTemplate(Path databasePath) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + databasePath);
        return new JdbcTemplate(dataSource);
    }
}
