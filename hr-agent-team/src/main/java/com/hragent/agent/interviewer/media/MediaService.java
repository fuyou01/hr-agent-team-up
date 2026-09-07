package com.hragent.agent.interviewer.media;

import com.hragent.agent.interviewer.model.InterviewSession;
import com.hragent.agent.interviewer.session.InterviewSessionService;
import com.hragent.agent.interviewer.persistence.InterviewSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 分片媒体的最小可靠存储：校验 sha256、幂等写入并保留可删除的引用。 */
@Service
public class MediaService {
    private final InterviewSessionService sessions;
    private final InterviewSessionRepository repository;
    private final MediaCrypto crypto = new MediaCrypto();
    private static final int MAX_CHUNK_BYTES = 20 * 1024 * 1024;
    private static final int MAX_MEDIA_ID_LENGTH = 128;
    private final Path root = Path.of(System.getProperty("hr.media.dir",
            System.getenv().getOrDefault("HR_MEDIA_DIR", "target/interview-media")))
            .toAbsolutePath().normalize();

    public MediaService(InterviewSessionService sessions) {
        this(sessions, new com.hragent.agent.interviewer.persistence.NoopInterviewSessionRepository());
    }

    @Autowired
    public MediaService(InterviewSessionService sessions, InterviewSessionRepository repository) {
        this.sessions = sessions;
        this.repository = repository;
    }

    public Map<String, Object> upload(String sessionId, Map<String, Object> request) {
        if (request == null) throw bad("媒体分片请求体不能为空");
        String mediaId = text(request.get("media_id"));
        String sha256 = text(request.get("sha256")).toLowerCase();
        int chunkNo = number(request.get("chunk_no"));
        String encoded = text(request.get("data_base64"));
        String codec = text(request.get("codec"));
        long startMs = longNumber(request.get("start_ms"));
        long endMs = longNumber(request.get("end_ms"));
        if (mediaId.isBlank() || mediaId.length() > MAX_MEDIA_ID_LENGTH || !mediaId.matches("[A-Za-z0-9_-]{1,128}")
                || sha256.isBlank() || chunkNo < 0 || chunkNo > 1_000_000 || encoded.isBlank() || codec.isBlank()
                || startMs < 0 || endMs < startMs) {
            throw bad("media_id、chunk_no、codec、start_ms、end_ms、sha256、data_base64 必填且时间有效");
        }
        if (encoded.length() > 28_000_000) throw bad("媒体分片 Base64 请求过大");
        if (!sha256.matches("[0-9a-f]{64}")) throw bad("sha256 必须是 64 位十六进制字符串");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw bad("data_base64 不是合法 Base64");
        }
        if (bytes.length > MAX_CHUNK_BYTES) throw bad("单个媒体分片不能超过 20MB");
        if (!sha256(bytes).equals(sha256)) throw bad("媒体分片 sha256 校验失败");
        InterviewSession session = sessions.get(sessionId);
        if ("none".equals(session.getRecordingMode())) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话未同意录制媒体");
        Path dir = root.resolve(safe(sessionId)).resolve(safe(mediaId)).normalize();
        if (!dir.startsWith(root)) throw bad("非法媒体引用");
        Path file = dir.resolve(chunkNo + ".chunk");
        for (Map<String, Object> old : repository.mediaChunks(sessionId, mediaId)) {
            if (chunkNo == ((Number) old.get("chunk_no")).intValue()
                    && (!sha256.equals(String.valueOf(old.get("sha256")))
                    || !codec.equals(String.valueOf(old.get("codec")))
                    || startMs != ((Number) old.get("start_ms")).longValue()
                    || endMs != ((Number) old.get("end_ms")).longValue())) {
                throw conflict("相同 chunk_no 的媒体元数据不能修改");
            }
        }
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                byte[] old = crypto.read(file);
                if (!MessageDigest.isEqual(old, bytes)) throw conflict("相同 chunk_no 已存在但内容不同");
            } else {
                crypto.write(file, bytes);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "媒体分片暂时无法保存", e);
        }
        Map<String, Object> event = new LinkedHashMap<>(request);
        event.remove("data_base64");
        event.put("type", "media_chunk");
        event.put("storage_ref", file.toString());
        event.put("upload_status", "stored");
        event.put("client_event_id", "media-" + mediaId + "-" + chunkNo);
        var updated = sessions.appendEvent(sessionId, event);
        repository.saveMediaChunk(sessionId, event);
        return sessions.candidateSnapshot(updated);
    }

    public Map<String, Object> chunks(String sessionId, String mediaId) {
        sessions.get(sessionId);
        var rows = repository.mediaChunks(sessionId, mediaId).stream().map(row -> {
            Map<String, Object> safe = new LinkedHashMap<>(row);
            safe.remove("storage_ref");
            return safe;
        }).toList();
        return Map.of("session_id", sessionId, "media_id", mediaId, "chunks", rows);
    }

    public Map<String, Object> complete(String sessionId, String mediaId, String expectedSha256) {
        sessions.get(sessionId);
        String safeSession = safe(sessionId), safeMedia = safe(mediaId);
        Path dir = root.resolve(safeSession).resolve(safeMedia).normalize();
        if (!dir.startsWith(root)) throw bad("非法媒体引用");
        var chunks = repository.mediaChunks(sessionId, mediaId);
        if (chunks.isEmpty()) throw bad("没有可合并的媒体分片");
        Path merged = dir.resolve("merged.media");
        try {
            Files.deleteIfExists(merged);
            long totalBytes = 0;
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (Exception e) { throw new IllegalStateException("无法初始化媒体摘要", e); }
            try (var out = crypto.openEncryptingOutput(merged)) {
                for (Map<String, Object> chunk : chunks) {
                    Path source = Path.of(String.valueOf(chunk.get("storage_ref"))).toAbsolutePath().normalize();
                    if (!source.startsWith(root) || !Files.exists(source)) throw bad("媒体分片缺失");
                    byte[] plaintext = crypto.read(source);
                    totalBytes += plaintext.length;
                    if (totalBytes > 512L * 1024 * 1024) throw bad("单段媒体总大小不能超过 512MB");
                    digest.update(plaintext);
                    out.write(plaintext);
                }
            }
            String digestHex = hex(digest.digest());
            if (expectedSha256 != null && !expectedSha256.isBlank() && !digestHex.equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(merged); throw bad("合并媒体 sha256 校验失败");
            }
            repository.markMediaCompleted(sessionId, mediaId, merged.toString(), digestHex);
            sessions.recordAudit(sessionId, "media_completed", Map.of("media_id", mediaId, "storage_ref", merged.toString(), "sha256", digestHex));
            return Map.of("session_id", sessionId, "media_id", mediaId, "storage_ref", merged.toString(), "sha256", digestHex, "upload_status", "completed");
        } catch (IOException e) {
            try { Files.deleteIfExists(merged); } catch (IOException ignored) { }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "媒体合并失败", e);
        } catch (RuntimeException e) {
            try { Files.deleteIfExists(merged); } catch (IOException ignored) { }
            throw e;
        }
    }

    public void delete(String sessionId) {
        sessions.get(sessionId);
        Path dir = root.resolve(safe(sessionId)).normalize();
        if (!dir.startsWith(root)) throw bad("非法媒体引用");
        try {
            if (Files.exists(dir)) {
                var failures = new java.util.ArrayList<IOException>();
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException e) { failures.add(e); }
                    });
                }
                if (!failures.isEmpty()) throw failures.get(0);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "媒体删除失败", e);
        }
    }

    public void deletePhysical(String sessionId) { delete(sessionId); }

    public void deleteMetadata(String sessionId) { sessions.get(sessionId); repository.deleteMediaMetadata(sessionId); }

    private String safe(String value) { return value.replaceAll("[^A-Za-z0-9_-]", "_"); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private int number(Object value) { return value instanceof Number ? ((Number) value).intValue() : -1; }
    private long longNumber(Object value) { return value instanceof Number ? ((Number) value).longValue() : -1L; }
    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return hex(digest);
        } catch (Exception e) { throw new IllegalStateException("无法计算媒体摘要", e); }
    }
    private String hex(byte[] digest) {
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }
    private String sha256File(Path file) {
        try (var in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("无法计算媒体摘要", e); }
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
