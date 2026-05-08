package edu.nyu.unidrive.server.service;

import static java.util.Map.entry;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class MockIdentityProvider {

    private static final Map<String, MockAccount> ACCOUNTS = Map.ofEntries(
        entry("student@nyu.edu", new MockAccount("rvg9395", "Student Demo", "student@nyu.edu", "STUDENT", "password123")),
        entry("rvg9395@nyu.edu", new MockAccount("rvg9395", "Rishikesh", "rvg9395@nyu.edu", "STUDENT", "password123")),
        entry("student2@nyu.edu", new MockAccount("ow2130", "Student Demo 2", "student2@nyu.edu", "STUDENT", "password123")),
        entry("ow2130@nyu.edu", new MockAccount("ow2130", "Omkar", "ow2130@nyu.edu", "STUDENT", "password123")),
        entry("student3@nyu.edu", new MockAccount("js1234", "Student Demo 3", "student3@nyu.edu", "STUDENT", "password123")),
        entry("js1234@nyu.edu", new MockAccount("js1234", "Jordan Student", "js1234@nyu.edu", "STUDENT", "password123")),
        entry("instructor@nyu.edu", new MockAccount("instructor_rvg0000", "Instructor Demo", "instructor@nyu.edu", "INSTRUCTOR", "password123")),
        entry("rvg0000@nyu.edu", new MockAccount("instructor_rvg0000", "Instructor Demo", "rvg0000@nyu.edu", "INSTRUCTOR", "password123")),
        entry("ta@nyu.edu", new MockAccount("instructor_ow0000", "TA Demo", "ta@nyu.edu", "INSTRUCTOR", "password123")),
        entry("ow0000@nyu.edu", new MockAccount("instructor_ow0000", "TA Demo", "ow0000@nyu.edu", "INSTRUCTOR", "password123"))
    );

    public Optional<AuthenticatedUser> authenticate(String email, String password) {
        if (email == null || password == null) {
            return Optional.empty();
        }
        MockAccount account = ACCOUNTS.get(email.trim().toLowerCase(Locale.ROOT));
        if (account == null || !account.password().equals(password)) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedUser(
            account.userId(),
            account.name(),
            account.email(),
            account.role(),
            "mock-sso-token-" + account.userId()
        ));
    }

    public record AuthenticatedUser(String userId, String name, String email, String role, String accessToken) {
    }

    private record MockAccount(String userId, String name, String email, String role, String password) {
    }
}
