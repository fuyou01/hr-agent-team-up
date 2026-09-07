package com.hragent.agent.interviewer.persistence;

import com.hragent.agent.interviewer.model.InterviewSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

/** 测试/非 Spring 构造时使用的内存空实现。生产 Bean 使用 JDBC 实现。 */
public class NoopInterviewSessionRepository implements InterviewSessionRepository {
    @Override public void save(InterviewSession session) { }
    @Override public Optional<InterviewSession> find(String sessionId) { return Optional.empty(); }
    @Override public List<InterviewSession> findAll() { return List.of(); }
    @Override public void saveMediaChunk(String sessionId, Map<String, Object> metadata) { }
    @Override public List<Map<String, Object>> mediaChunks(String sessionId, String mediaId) { return List.of(); }
    @Override public void markMediaCompleted(String sessionId, String mediaId, String mergedRef, String sha256) { }
    @Override public void deleteMediaMetadata(String sessionId) { }
    @Override public List<String> findExpiredSessionIds(Instant cutoff) { return List.of(); }
    @Override public void deleteSessionData(String sessionId) { }
}
