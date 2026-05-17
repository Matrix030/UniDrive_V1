package edu.nyu.unidrive.server.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.nyu.unidrive.server.database.AssignmentSchemaMigrator;
import edu.nyu.unidrive.server.database.VersionCounter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AssignmentRepositoryVersionTest {

    @Test
    void insertAndUpdateBumpVersionMonotonically(@TempDir Path tempDir) {
        Fixture fx = newFixture(tempDir);

        fx.repo.save("hw1", "spec.md", "fall2026", "daa", "HW 1", 0L, 100L, "/p/hw1", "h1");
        fx.repo.save("hw2", "spec.md", "fall2026", "daa", "HW 2", 0L, 200L, "/p/hw2", "h2");

        long v1 = fx.versionOf("hw1", "spec.md");
        long v2 = fx.versionOf("hw2", "spec.md");
        assertTrue(v2 > v1, "second insert should bump version above the first");

        fx.repo.save("hw1", "spec.md", "fall2026", "daa", "HW 1 updated", 0L, 300L, "/p/hw1", "h1b");
        long v1Updated = fx.versionOf("hw1", "spec.md");
        assertTrue(v1Updated > v2, "update should bump version above any prior version");
    }

    @Test
    void softDeleteBumpsVersionAndMarksDeleted(@TempDir Path tempDir) {
        Fixture fx = newFixture(tempDir);

        fx.repo.save("hw1", "spec.md", "fall2026", "daa", "HW 1", 0L, 100L, "/p/hw1", "h1");
        long beforeDelete = fx.versionOf("hw1", "spec.md");

        fx.repo.deleteByIdAndFileName("hw1", "spec.md");

        Long deleted = fx.jdbcTemplate.queryForObject(
            "SELECT deleted FROM assignments WHERE id = 'hw1' AND file_name = 'spec.md'", Long.class);
        Long deletedAt = fx.jdbcTemplate.queryForObject(
            "SELECT deleted_at FROM assignments WHERE id = 'hw1' AND file_name = 'spec.md'", Long.class);
        Long versionAfter = fx.jdbcTemplate.queryForObject(
            "SELECT version FROM assignments WHERE id = 'hw1' AND file_name = 'spec.md'", Long.class);
        assertEquals(1L, deleted);
        assertNotNull(deletedAt);
        assertTrue(versionAfter > beforeDelete, "soft-delete must bump version");

        assertTrue(fx.repo.findStoredAssignmentByIdAndFileName("hw1", "spec.md").isEmpty(),
            "soft-deleted rows must not surface in live reads");
    }

    private Fixture newFixture(Path tempDir) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("assignments.db"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);

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
        new AssignmentSchemaMigrator(jdbcTemplate, txManager).migrate();
        VersionCounter versionCounter = new VersionCounter(jdbcTemplate, txManager);
        return new Fixture(jdbcTemplate, new AssignmentRepository(jdbcTemplate, versionCounter));
    }

    private record Fixture(JdbcTemplate jdbcTemplate, AssignmentRepository repo) {
        long versionOf(String id, String fileName) {
            Long v = jdbcTemplate.queryForObject(
                "SELECT version FROM assignments WHERE id = ? AND file_name = ?", Long.class, id, fileName);
            assertNotNull(v);
            return v;
        }
    }
}
