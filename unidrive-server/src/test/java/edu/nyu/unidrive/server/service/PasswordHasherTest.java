package edu.nyu.unidrive.server.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private static final String HASH = "pbkdf2_sha256$120000$dW5pZHJpdmUtZGVtby1ydmc5Mzk1$579X2tAHa43VVOcrHONnkpe2BW0FVhuq6XcucOFAxm0=";

    @Test
    void verifiesMatchingPasswordAgainstStoredHash() {
        assertTrue(PasswordHasher.verify("password123", HASH));
    }

    @Test
    void rejectsWrongPasswordAndMalformedHash() {
        assertFalse(PasswordHasher.verify("wrong", HASH));
        assertFalse(PasswordHasher.verify("password123", "password123"));
    }
}
