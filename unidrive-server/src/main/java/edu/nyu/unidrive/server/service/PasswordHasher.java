package edu.nyu.unidrive.server.service;

import java.security.MessageDigest;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String HASH_PREFIX = "pbkdf2_sha256";
    private static final int KEY_LENGTH_BITS = 256;

    private PasswordHasher() {
    }

    public static boolean verify(String password, String encodedHash) {
        if (password == null || encodedHash == null || encodedHash.isBlank()) {
            return false;
        }
        String[] parts = encodedHash.split("\\$", -1);
        if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[3]);
            byte[] actualHash = pbkdf2(password.toCharArray(), salt, iterations, expectedHash.length * 8);
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits <= 0 ? KEY_LENGTH_BITS : keyLengthBits);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash password.", exception);
        }
    }
}
