package com.hragent.agent.interviewer;

import com.hragent.agent.interviewer.media.MediaCrypto;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MediaCryptoTest {
    @Test
    void encryptedOrDevelopmentMediaRoundTrips() throws Exception {
        MediaCrypto crypto = new MediaCrypto();
        var path = Files.createTempFile("interview-media", ".chunk");
        Files.deleteIfExists(path);
        byte[] source = "candidate-media".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        crypto.write(path, source);
        assertArrayEquals(source, crypto.read(path));
        Files.deleteIfExists(path);
    }
}
