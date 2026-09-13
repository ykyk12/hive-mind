package com.hivemind.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 哈希工具：稳定分桶（灰度/路由）与摘要（密钥不落明文）。 */
public final class Hashes {

    private Hashes() {
    }

    /** 稳定分桶：同一输入永远落同一个桶，用于灰度分流与路由复现。 */
    public static int stableBucket(String input, int buckets) {
        if (input == null || input.isBlank() || buckets <= 0) {
            return 0;
        }
        byte[] digest = sha256(input);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (digest[i] & 0xFFL);
        }
        return (int) Math.floorMod(value, buckets);
    }

    public static String sha256Hex(String input) {
        byte[] digest = sha256(input);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
