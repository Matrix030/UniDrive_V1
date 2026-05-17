package edu.nyu.unidrive.server.database;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class VersionCounter {

    public static final String TABLE_ASSIGNMENTS = "assignments";
    public static final String TABLE_SUBMISSIONS = "submissions";
    public static final String TABLE_FEEDBACK = "feedback";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public VersionCounter(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public synchronized long allocate(String tableName) {
        Long allocated = transactionTemplate.execute(status -> {
            Long current = jdbcTemplate.query(
                "SELECT next_val FROM version_counter WHERE table_name = ?",
                rs -> rs.next() ? rs.getLong("next_val") : null,
                tableName);
            long next = current == null ? 1L : current;
            jdbcTemplate.update(
                "INSERT INTO version_counter (table_name, next_val) VALUES (?, ?) "
                    + "ON CONFLICT(table_name) DO UPDATE SET next_val = ?",
                tableName, next + 1L, next + 1L);
            return next;
        });
        if (allocated == null) {
            throw new IllegalStateException("Failed to allocate version for " + tableName);
        }
        return allocated;
    }
}
