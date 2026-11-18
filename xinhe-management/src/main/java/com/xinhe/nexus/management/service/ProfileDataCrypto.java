package com.xinhe.nexus.management.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProfileDataCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public ProfileDataCrypto(@Value("${xinhe.profile.data-encryption-key:${xinhe.onboarding.token-encryption-key:change-this-onboarding-token-key-in-production}}") String encryptionKey) {
        this.key = new SecretKeySpec(sha256(encryptionKey), "AES");
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("个人敏感信息加密失败", ex);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        try {
            byte[] value = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(value, 0, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(value, 12, value.length)), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("个人敏感信息解密失败", ex);
        }
    }

    public String mask(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() <= 4) return "****";
        return "*".repeat(Math.max(0, trimmed.length() - 4)) + trimmed.substring(trimmed.length() - 4);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
