package com.hragent.agent.interviewer.persistence;

import com.hragent.agent.interviewer.model.InterviewSession;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.time.Instant;

/** 会话快照与不可变事件的持久化边界。 */
public interface InterviewSessionRepository {
    void save(InterviewSession session);
    Optional<InterviewSession> find(String sessionId);
    List<InterviewSession> findAll();
    void saveMediaChunk(String sessionId, Map<String, Object> metadata);
    List<Map<String, Object>> mediaChunks(String sessionId, String mediaId);
    void markMediaCompleted(String sessionId, String mediaId, String mergedRef, String sha256);
    void deleteMediaMetadata(String sessionId);
    List<String> findExpiredSessionIds(Instant cutoff);
    void deleteSessionData(String sessionId);
}
