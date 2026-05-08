package edu.nyu.unidrive.server.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:sqlite:target/test-users-repo.db"
})
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearUsers() {
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    void findByIdReturnsEmptyWhenUserAbsent() {
        assertTrue(userRepository.findById("missing").isEmpty());
    }

    @Test
    void saveAndFindByIdRoundTripsUserRecord() {
        String passwordHash = "pbkdf2_sha256$120000$salt$hash";
        userRepository.save("rvg9395", "Rishikesh", "STUDENT", passwordHash);

        Optional<UserRepository.StoredUser> found = userRepository.findById("rvg9395");

        assertTrue(found.isPresent());
        assertEquals("rvg9395", found.get().id());
        assertEquals("Rishikesh", found.get().name());
        assertEquals("STUDENT", found.get().role());
        assertEquals(passwordHash, found.get().passwordHash());
    }
}
