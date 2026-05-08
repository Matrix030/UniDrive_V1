package edu.nyu.unidrive.server.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initializeSchema();
    }

    public Optional<StoredUser> findById(String userId) {
        List<StoredUser> users = jdbcTemplate.query(
            "SELECT id, name, role, password_hash FROM users WHERE id = ?",
            (resultSet, rowNum) -> new StoredUser(
                resultSet.getString("id"),
                resultSet.getString("name"),
                resultSet.getString("role"),
                resultSet.getString("password_hash")
            ),
            userId
        );
        return users.stream().findFirst();
    }

    public void save(String id, String name, String role, String passwordHash) {
        jdbcTemplate.update(
            "INSERT INTO users (id, name, role, password_hash) VALUES (?, ?, ?, ?)",
            id,
            name,
            role,
            passwordHash
        );
    }

    public void updateProfile(String id, String name, String role, String passwordHash) {
        jdbcTemplate.update(
            "UPDATE users SET name = ?, role = ?, password_hash = ? WHERE id = ?",
            name,
            role,
            passwordHash,
            id
        );
    }

    private void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                name TEXT,
                role TEXT,
                password_hash TEXT
            )
            """);
        if (!hasColumn("users", "password_hash")) {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN password_hash TEXT");
        }
    }

    private boolean hasColumn(String tableName, String columnName) {
        return jdbcTemplate.query(
            "PRAGMA table_info(" + tableName + ")",
            (resultSet, rowNum) -> resultSet.getString("name")
        ).stream().anyMatch(columnName::equalsIgnoreCase);
    }

    public record StoredUser(String id, String name, String role, String passwordHash) {
    }
}
