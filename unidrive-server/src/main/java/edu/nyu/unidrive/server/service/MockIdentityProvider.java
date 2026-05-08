package edu.nyu.unidrive.server.service;

import static java.util.Map.entry;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class MockIdentityProvider {

    private static final Map<String, MockAccount> ACCOUNTS = Map.ofEntries(
        entry("student@nyu.edu", new MockAccount("rvg9395", "Student Demo", "student@nyu.edu", "STUDENT", DemoPasswordHashes.RVG9395)),
        entry("rvg9395@nyu.edu", new MockAccount("rvg9395", "Rishikesh", "rvg9395@nyu.edu", "STUDENT", DemoPasswordHashes.RVG9395)),
        entry("student2@nyu.edu", new MockAccount("ow2130", "Student Demo 2", "student2@nyu.edu", "STUDENT", DemoPasswordHashes.OW2130)),
        entry("ow2130@nyu.edu", new MockAccount("ow2130", "Omkar", "ow2130@nyu.edu", "STUDENT", DemoPasswordHashes.OW2130)),
        entry("student3@nyu.edu", new MockAccount("js1234", "Student Demo 3", "student3@nyu.edu", "STUDENT", DemoPasswordHashes.JS1234)),
        entry("js1234@nyu.edu", new MockAccount("js1234", "Jordan Student", "js1234@nyu.edu", "STUDENT", DemoPasswordHashes.JS1234)),
        entry("instructor@nyu.edu", new MockAccount("instructor_rvg0000", "Instructor Demo", "instructor@nyu.edu", "INSTRUCTOR", DemoPasswordHashes.INSTRUCTOR_RVG0000)),
        entry("rvg0000@nyu.edu", new MockAccount("instructor_rvg0000", "Instructor Demo", "rvg0000@nyu.edu", "INSTRUCTOR", DemoPasswordHashes.INSTRUCTOR_RVG0000)),
        entry("ta@nyu.edu", new MockAccount("instructor_ow0000", "TA Demo", "ta@nyu.edu", "INSTRUCTOR", DemoPasswordHashes.INSTRUCTOR_OW0000)),
        entry("ow0000@nyu.edu", new MockAccount("instructor_ow0000", "TA Demo", "ow0000@nyu.edu", "INSTRUCTOR", DemoPasswordHashes.INSTRUCTOR_OW0000))
    );

    public Optional<AuthenticatedUser> authenticate(String email, String password) {
        if (email == null || password == null) {
            return Optional.empty();
        }
        MockAccount account = ACCOUNTS.get(email.trim().toLowerCase(Locale.ROOT));
        if (account == null || !PasswordHasher.verify(password, account.passwordHash())) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedUser(
            account.userId(),
            account.name(),
            account.email(),
            account.role(),
            account.passwordHash(),
            "mock-sso-token-" + account.userId()
        ));
    }

    public record AuthenticatedUser(String userId, String name, String email, String role, String passwordHash, String accessToken) {
    }

    private record MockAccount(String userId, String name, String email, String role, String passwordHash) {
    }

    private static final class DemoPasswordHashes {
        private static final String RVG9395 = "pbkdf2_sha256$120000$dW5pZHJpdmUtZGVtby1ydmc5Mzk1$579X2tAHa43VVOcrHONnkpe2BW0FVhuq6XcucOFAxm0=";
        private static final String OW2130 = "pbkdf2_sha256$120000$dW5pZHJpdmUtZGVtby1vdzIxMzA=$HsxYpTKhvOzUPpf4QnSIRgjVY4nlQeZBudfov8Q/x00=";
        private static final String JS1234 = "pbkdf2_sha256$120000$dW5pZHJpdmUtZGVtby1qczEyMzQ=$CTI7NKQ9eCpkedf1BoNEsNy2s3Aj6pNzwfvkk7hY4rw=";
        private static final String INSTRUCTOR_RVG0000 = "pbkdf2_sha256$120000$dW5pZHJpdmUtZGVtby1pbnN0cnVjdG9yX3J2ZzAwMDA=$+tqHNkWXZjPq2G5QQzEmysyx1ZRIqXwgA6zQu4Y5omk=";
        private static final String INSTRUCTOR_OW0000 = "pbkdf2_sha256$120000$dW5pZHJpdmUtZGVtby1pbnN0cnVjdG9yX293MDAwMA==$oLMVdLlkd70s8KUi+2KK5s2Hi6QLjPJU6l6wTnT4ELQ=";

        private DemoPasswordHashes() {
        }
    }
}
