package com.hragent.agent.interviewer.media;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM 媒体静态加密；未配置密钥时仅用于本地开发，生产应设置 HR_MEDIA_ENCRYPTION_KEY。 */
public final class MediaCrypto {
    private static final int IV_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MediaCrypto() {
        String raw = System.getenv().getOrDefault("HR_MEDIA_ENCRYPTION_KEY", "").trim();
        if (raw.isBlank()) {
            boolean production = "prod".equalsIgnoreCase(System.getenv().getOrDefault("HR_ENV", "dev"));
            if (production || Boolean.parseBoolean(System.getenv().getOrDefault("HR_REQUIRE_MEDIA_ENCRYPTION", "false"))) {
                throw new IllegalStateException("生产环境必须配置 HR_MEDIA_ENCRYPTION_KEY");
            }
            key = null;
            return;
        }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(raw); }
        catch (IllegalArgumentException e) { bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalStateException("HR_MEDIA_ENCRYPTION_KEY 必须是 16/24/32 字节 Base64 或文本密钥");
        }
        key = new SecretKeySpec(bytes, "AES");
    }

    public boolean enabled() { return key != null; }

    public void write(Path path, byte[] plaintext) throws IOException {
        try (OutputStream out = openEncryptingOutput(path)) { out.write(plaintext); }
    }

    public byte[] read(Path path) throws IOException {
        if (!enabled()) return Files.readAllBytes(path);
        try (InputStream in = openDecryptingInput(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out); return out.toByteArray();
        }
    }

    public OutputStream openEncryptingOutput(Path path) throws IOException {
        OutputStream raw = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW);
        if (!enabled()) return raw;
        byte[] iv = new byte[IV_BYTES]; random.nextBytes(iv); raw.write(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new CipherOutputStream(raw, cipher);
        } catch (Exception e) { try { raw.close(); } catch (IOException ignored) { } throw new IOException("无法初始化媒体加密", e); }
    }

    private InputStream openDecryptingInput(Path path) throws IOException {
        InputStream raw = Files.newInputStream(path);
        if (!enabled()) return raw;
        byte[] iv = raw.readNBytes(IV_BYTES);
        if (iv.length != IV_BYTES) throw new IOException("媒体加密头损坏");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new CipherInputStream(raw, cipher);
        } catch (Exception e) { try { raw.close(); } catch (IOException ignored) { } throw new IOException("无法初始化媒体解密", e); }
    }
}
