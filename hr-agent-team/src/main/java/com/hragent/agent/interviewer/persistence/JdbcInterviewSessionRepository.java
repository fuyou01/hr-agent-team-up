package com.hragent.agent.interviewer.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hragent.agent.interviewer.model.InterviewSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcInterviewSessionRepository implements InterviewSessionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean postgres;

    public JdbcInterviewSessionRepository(JdbcTemplate jdbc, ObjectMapper mapper, DataSource dataSource) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        try (var connection = dataSource.getConnection()) {
            this.postgres = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (Exception e) { throw new IllegalStateException("无法识别数据库类型", e); }
    }

    @Override
    @Transactional
    public synchronized void save(InterviewSession session) {
        String json = write(session.stateSnapshot());
        if (postgres) {
            jdbc.update("INSERT INTO interview_session (session_id, state_json, status, created_at, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) ON CONFLICT (session_id) DO UPDATE SET state_json=EXCLUDED.state_json, status=EXCLUDED.status, updated_at=CURRENT_TIMESTAMP",
                    session.getSessionId(), json, session.getStatus().name(), session.getCreatedAt());
        } else {
            jdbc.update("MERGE INTO interview_session (session_id, state_json, status, created_at, updated_at) KEY(session_id) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    session.getSessionId(), json, session.getStatus().name(), session.getCreatedAt());
        }
        long persistedSequence = jdbc.queryForObject("SELECT COALESCE(MAX(server_sequence), 0) FROM interview_event WHERE session_id = ?", Long.class, session.getSessionId());
        for (Map<String, Object> event : session.eventsSnapshot()) {
            if (number(event.get("sequence")) <= persistedSequence) continue;
            String clientId = String.valueOf(event.getOrDefault("client_event_id", ""));
            if (postgres) {
                jdbc.update("INSERT INTO interview_event (session_id, event_id, client_event_id, server_sequence, client_sequence, event_type, event_json, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (session_id, client_event_id) DO NOTHING",
                        session.getSessionId(), String.valueOf(event.getOrDefault("event_id", "")), clientId,
                        number(event.get("sequence")), nullableNumber(event.get("client_sequence")),
                        String.valueOf(event.getOrDefault("type", "unknown")), write(event),
                        String.valueOf(event.getOrDefault("occurred_at", Instant.now().toString())));
            } else {
                jdbc.update("MERGE INTO interview_event (session_id, event_id, client_event_id, server_sequence, client_sequence, event_type, event_json, occurred_at) KEY(session_id, client_event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        session.getSessionId(), String.valueOf(event.getOrDefault("event_id", "")), clientId,
                        number(event.get("sequence")), nullableNumber(event.get("client_sequence")),
                        String.valueOf(event.getOrDefault("type", "unknown")), write(event),
                        String.valueOf(event.getOrDefault("occurred_at", Instant.now().toString())));
            }
        }
    }

    @Override
    public Optional<InterviewSession> find(String sessionId) {
        List<String> rows = jdbc.query("SELECT state_json FROM interview_session WHERE session_id = ?", (rs, n) -> rs.getString(1), sessionId);
        return rows.stream().findFirst().map(this::restore);
    }

    @Override
    public List<InterviewSession> findAll() {
        return jdbc.query("SELECT state_json FROM interview_session", (rs, n) -> restore(rs.getString(1)));
    }

    @Override
    @Transactional
    public void saveMediaChunk(String sessionId, Map<String, Object> metadata) {
        if (postgres) {
            jdbc.update("INSERT INTO media_chunk (session_id, media_id, chunk_no, sha256, storage_ref, upload_status, codec, start_ms, end_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (session_id, media_id, chunk_no) DO UPDATE SET sha256=EXCLUDED.sha256, storage_ref=EXCLUDED.storage_ref, upload_status=EXCLUDED.upload_status, codec=EXCLUDED.codec, start_ms=EXCLUDED.start_ms, end_ms=EXCLUDED.end_ms",
                    sessionId, text(metadata, "media_id"), number(metadata.get("chunk_no")), text(metadata, "sha256"),
                    text(metadata, "storage_ref"), text(metadata, "upload_status"), text(metadata, "codec"),
                    longNumber(metadata.get("start_ms")), longNumber(metadata.get("end_ms")));
        } else {
            jdbc.update("MERGE INTO media_chunk (session_id, media_id, chunk_no, sha256, storage_ref, upload_status, codec, start_ms, end_ms) KEY(session_id, media_id, chunk_no) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    sessionId, text(metadata, "media_id"), number(metadata.get("chunk_no")), text(metadata, "sha256"),
                    text(metadata, "storage_ref"), text(metadata, "upload_status"), text(metadata, "codec"),
                    longNumber(metadata.get("start_ms")), longNumber(metadata.get("end_ms")));
        }
    }

    @Override
    public List<Map<String, Object>> mediaChunks(String sessionId, String mediaId) {
        return jdbc.query("SELECT media_id, chunk_no, sha256, storage_ref, upload_status, codec, start_ms, end_ms FROM media_chunk WHERE session_id = ? AND media_id = ? ORDER BY chunk_no", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("media_id", rs.getString("media_id")); row.put("chunk_no", rs.getInt("chunk_no"));
            row.put("sha256", rs.getString("sha256")); row.put("storage_ref", rs.getString("storage_ref"));
            row.put("upload_status", rs.getString("upload_status")); row.put("codec", rs.getString("codec"));
            row.put("start_ms", rs.getLong("start_ms")); row.put("end_ms", rs.getLong("end_ms"));
            return row;
        }, sessionId, mediaId);
    }

    @Override
    @Transactional
    public void markMediaCompleted(String sessionId, String mediaId, String mergedRef, String sha256) {
        jdbc.update("UPDATE media_chunk SET upload_status = 'completed', merged_ref = ?, merged_sha256 = ? WHERE session_id = ? AND media_id = ?",
                mergedRef, sha256, sessionId, mediaId);
    }

    @Override
    @Transactional
    public void deleteMediaMetadata(String sessionId) {
        jdbc.update("DELETE FROM media_chunk WHERE session_id = ?", sessionId);
    }

    @Override
    public List<String> findExpiredSessionIds(Instant cutoff) {
        return jdbc.query("SELECT session_id FROM interview_session WHERE updated_at < ?", (rs, n) -> rs.getString(1), cutoff);
    }

    @Override
    @Transactional
    public void deleteSessionData(String sessionId) {
        jdbc.update("DELETE FROM media_chunk WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM interview_event WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM interview_session WHERE session_id = ?", sessionId);
    }

    private InterviewSession restore(String json) {
        try {
            Map<String, Object> state = mapper.readValue(json, new TypeReference<>() {});
            Instant created = Instant.parse(String.valueOf(state.getOrDefault("created_at", Instant.now().toString())));
            InterviewSession session = new InterviewSession(
                    String.valueOf(state.get("session_id")), map(state.get("candidate")), map(state.get("jd")), map(state.get("resume")),
                    Boolean.TRUE.equals(state.get("online")), String.valueOf(state.getOrDefault("industry", "computer")),
                    Boolean.TRUE.equals(state.get("industry_defaulted")), created);
            session.restoreState(state);
            return session;
        } catch (Exception e) {
            throw new IllegalStateException("无法恢复面试会话快照", e);
        }
    }

    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
    }
    private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("无法序列化面试数据", e); } }
    private String text(Map<String, Object> map, String key) { return String.valueOf(map.getOrDefault(key, "")); }
    private int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private Object nullableNumber(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private long longNumber(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
}
