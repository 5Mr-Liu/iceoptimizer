package dev.rlcraft.ice.optimizer.lock;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ClassFingerprint {
    private ClassFingerprint() {
    }

    public static String sha256(byte[] bytes) {
        MessageDigest digest = digest();
        digest.update(bytes);
        return hex(digest.digest());
    }

    public static String shortSha256(byte[] bytes) {
        return sha256(bytes).substring(0, 16);
    }

    public static String sha256(File file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        InputStream input = new BufferedInputStream(new FileInputStream(file));
        try {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        } finally {
            input.close();
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", impossible);
        }
    }

    private static String hex(byte[] value) {
        char[] result = new char[value.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) {
            int current = value[i] & 0xff;
            result[i * 2] = digits[current >>> 4];
            result[i * 2 + 1] = digits[current & 0x0f];
        }
        return new String(result);
    }
}
