package dev.rlcraft.ice.hooks;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 implementation kept inside the standalone CoreMod JAR. */
final class CoreClassFingerprint {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CoreClassFingerprint() {
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                result[i * 2] = HEX[value >>> 4];
                result[i * 2 + 1] = HEX[value & 0x0f];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", impossible);
        }
    }
}
