package edu.nyu.unidrive.server.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class VersionCounterTest {

    @Test
    void allocateReturnsSequentialValues(@TempDir Path tempDir) {
        VersionCounter counter = newCounter(tempDir);

        long a = counter.allocate(VersionCounter.TABLE_ASSIGNMENTS);
        long b = counter.allocate(VersionCounter.TABLE_ASSIGNMENTS);
        long c = counter.allocate(VersionCounter.TABLE_ASSIGNMENTS);

        assertEquals(1L, a);
        assertEquals(2L, b);
        assertEquals(3L, c);
    }

    @Test
    void allocateIsThreadSafeUnderConcurrentInserts(@TempDir Path tempDir) throws Exception {
        VersionCounter counter = newCounter(tempDir);

        int threads = 4;
        int allocationsPerThread = 50;
        int total = threads * allocationsPerThread;
        ConcurrentLinkedQueue<Long> allocated = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < allocationsPerThread; j++) {
                        allocated.add(counter.allocate(VersionCounter.TABLE_SUBMISSIONS));
                    }
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "executor did not finish in time");
        assertTrue(failures.isEmpty(), () -> "threads threw: " + failures);

        Set<Long> distinct = new HashSet<>(allocated);
        assertEquals(total, distinct.size(), "every allocation must be unique");
        long max = distinct.stream().mapToLong(Long::longValue).max().orElseThrow();
        long min = distinct.stream().mapToLong(Long::longValue).min().orElseThrow();
        assertEquals(1L, min);
        assertEquals(total, max);
    }

    private VersionCounter newCounter(Path tempDir) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("vc.db"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE version_counter (
                table_name TEXT PRIMARY KEY,
                next_val INTEGER NOT NULL
            )
            """);
        return new VersionCounter(jdbcTemplate, tx);
    }
}
