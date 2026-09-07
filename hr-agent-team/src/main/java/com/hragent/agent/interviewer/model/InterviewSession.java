package com.hragent.agent.interviewer.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InterviewSession {
    private final String sessionId;
    private final Map<String, Object> candidate;
    private final Map<String, Object> jd;
    private final Map<String, Object> resume;
    private final boolean online;
    private final String industry;
    private final boolean industryDefaulted;
    private final Instant createdAt;
    private volatile String accessTokenHash = "";
    private volatile InterviewStatus status = InterviewStatus.INITIALIZED;
    private volatile String recordingMode = "none";
    private volatile String camera = "unknown";
    private volatile String microphone = "unknown";
    private volatile Map<String, Object> promptPack = new LinkedHashMap<>();
    private volatile Map<String, Object> plan = new LinkedHashMap<>();
    private volatile Map<String, Object> minutes = new LinkedHashMap<>();
    private volatile Map<String, Object> scorecard = new LinkedHashMap<>();
    private volatile Map<String, Object> handoff = new LinkedHashMap<>();
    private volatile List<Map<String, Object>> artifacts = new ArrayList<>();
    private volatile String reviewStatus = "pending";
    private volatile Map<String, String> questionDimensions = new LinkedHashMap<>();
    private volatile boolean scoreRequested;
    private long nextSequence = 1;
    private long lastClientSequence = 0;
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final List<Map<String, Object>> responses = new ArrayList<>();

    public InterviewSession(String sessionId, Map<String, Object> candidate,
                            Map<String, Object> jd, Map<String, Object> resume,
                            boolean online, String industry, boolean industryDefaulted) {
        this.sessionId = sessionId;
        this.candidate = copy(candidate);
        this.jd = copy(jd);
        this.resume = copy(resume);
        this.online = online;
        this.industry = industry;
        this.industryDefaulted = industryDefaulted;
        this.createdAt = Instant.now();
    }

    public InterviewSession(String sessionId, Map<String, Object> candidate,
                            Map<String, Object> jd, Map<String, Object> resume,
                            boolean online, String industry, boolean industryDefaulted,
                            Instant createdAt) {
        this.sessionId = sessionId;
        this.candidate = copy(candidate);
        this.jd = copy(jd);
        this.resume = copy(resume);
        this.online = online;
        this.industry = industry;
        this.industryDefaulted = industryDefaulted;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    public synchronized Map<String, Object> appendEvent(Map<String, Object> input) {
        Map<String, Object> event = new LinkedHashMap<>(input == null ? Map.of() : input);
        String clientEventId = String.valueOf(event.getOrDefault("client_event_id", ""));
        if (!clientEventId.isBlank()) {
            for (Map<String, Object> old : events) {
                if (clientEventId.equals(String.valueOf(old.get("client_event_id")))) {
                    return old;
                }
            }
        }
        event.put("session_id", sessionId);
        event.putIfAbsent("event_id", "E-" + UUID.randomUUID());
        if (clientEventId.isBlank()) {
            clientEventId = "server-" + event.get("event_id");
            event.put("client_event_id", clientEventId);
        }
        Object requestedSequence = event.remove("sequence");
        if (requestedSequence instanceof Number) {
            long clientSequence = ((Number) requestedSequence).longValue();
            if (clientSequence <= lastClientSequence) {
                throw new IllegalArgumentException("客户端事件 sequence 必须递增");
            }
            lastClientSequence = clientSequence;
            event.put("client_sequence", clientSequence);
        }
        event.put("sequence", nextSequence++);
        event.putIfAbsent("occurred_at", Instant.now().toString());
        events.add(event);
        if ("response".equals(event.get("type"))) {
            responses.add(event);
        }
        return event;
    }

    public synchronized List<Map<String, Object>> eventsSnapshot() {
        return new ArrayList<>(events);
    }

    public synchronized List<Map<String, Object>> responsesSnapshot() {
        return new ArrayList<>(responses);
    }

    public synchronized Map<String, Object> stateSnapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("session_id", sessionId);
        state.put("candidate", getCandidate());
        state.put("jd", getJd());
        state.put("resume", getResume());
        state.put("online", online);
        state.put("industry", industry);
        state.put("industry_defaulted", industryDefaulted);
        state.put("created_at", createdAt.toString());
        state.put("access_token_hash", accessTokenHash);
        state.put("status", status.name());
        state.put("recording_mode", recordingMode);
        state.put("camera", camera);
        state.put("microphone", microphone);
        state.put("prompt_pack", getPromptPack());
        state.put("plan", getPlan());
        state.put("minutes", getMinutes());
        state.put("scorecard", getScorecard());
        state.put("handoff", getHandoff());
        state.put("artifacts", getArtifacts());
        state.put("review_status", reviewStatus);
        state.put("question_dimensions", getQuestionDimensions());
        state.put("score_requested", scoreRequested);
        state.put("events", eventsSnapshot());
        return state;
    }

    @SuppressWarnings("unchecked")
    public synchronized void restoreState(Map<String, Object> state) {
        if (state == null) return;
        Object rawStatus = state.get("status");
        if (rawStatus != null) status = InterviewStatus.valueOf(String.valueOf(rawStatus));
        recordingMode = String.valueOf(state.getOrDefault("recording_mode", recordingMode));
        camera = String.valueOf(state.getOrDefault("camera", camera));
        microphone = String.valueOf(state.getOrDefault("microphone", microphone));
        promptPack = copyMap(state.get("prompt_pack"));
        plan = copyMap(state.get("plan"));
        minutes = copyMap(state.get("minutes"));
        scorecard = copyMap(state.get("scorecard"));
        handoff = copyMap(state.get("handoff"));
        reviewStatus = String.valueOf(state.getOrDefault("review_status", reviewStatus));
        questionDimensions = copyStringMap(state.get("question_dimensions"));
        scoreRequested = Boolean.TRUE.equals(state.get("score_requested"));
        accessTokenHash = String.valueOf(state.getOrDefault("access_token_hash", accessTokenHash));
        events.clear();
        responses.clear();
        nextSequence = 1;
        lastClientSequence = 0;
        Object rawEvents = state.get("events");
        if (rawEvents instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> raw)) continue;
                Map<String, Object> event = new LinkedHashMap<>((Map<String, Object>) raw);
                events.add(event);
                Object sequence = event.get("sequence");
                if (sequence instanceof Number n) nextSequence = Math.max(nextSequence, n.longValue() + 1);
                Object clientSequence = event.get("client_sequence");
                if (clientSequence instanceof Number n) lastClientSequence = Math.max(lastClientSequence, n.longValue());
                if ("response".equals(event.get("type"))) responses.add(event);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> copyStringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }

    public String getSessionId() { return sessionId; }
    public Map<String, Object> getCandidate() { return new LinkedHashMap<>(candidate); }
    public Map<String, Object> getJd() { return new LinkedHashMap<>(jd); }
    public Map<String, Object> getResume() { return new LinkedHashMap<>(resume); }
    public boolean isOnline() { return online; }
    public String getIndustry() { return industry; }
    public boolean isIndustryDefaulted() { return industryDefaulted; }
    public Instant getCreatedAt() { return createdAt; }
    public String getAccessTokenHash() { return accessTokenHash; }
    public void setAccessTokenHash(String accessTokenHash) { this.accessTokenHash = accessTokenHash == null ? "" : accessTokenHash; }
    public InterviewStatus getStatus() { return status; }
    public void setStatus(InterviewStatus status) { this.status = status; }
    public String getRecordingMode() { return recordingMode; }
    public void setRecordingMode(String recordingMode) { this.recordingMode = recordingMode; }
    public String getCamera() { return camera; }
    public void setCamera(String camera) { this.camera = camera; }
    public String getMicrophone() { return microphone; }
    public void setMicrophone(String microphone) { this.microphone = microphone; }
    public Map<String, Object> getPromptPack() { return new LinkedHashMap<>(promptPack); }
    public void setPromptPack(Map<String, Object> promptPack) { this.promptPack = copy(promptPack); }
    public Map<String, Object> getPlan() { return new LinkedHashMap<>(plan); }
    public void setPlan(Map<String, Object> plan) { this.plan = copy(plan); }
    public Map<String, Object> getMinutes() { return new LinkedHashMap<>(minutes); }
    public void setMinutes(Map<String, Object> minutes) { this.minutes = copy(minutes); }
    public Map<String, Object> getScorecard() { return new LinkedHashMap<>(scorecard); }
    public void setScorecard(Map<String, Object> scorecard) { this.scorecard = copy(scorecard); }
    public Map<String, Object> getHandoff() { return new LinkedHashMap<>(handoff); }
    public void setHandoff(Map<String, Object> handoff) { this.handoff = copy(handoff); }
    public List<Map<String, Object>> getArtifacts() { return new ArrayList<>(artifacts); }
    public void setArtifacts(List<Map<String, Object>> artifacts) {
        this.artifacts = artifacts == null ? new ArrayList<>() : new ArrayList<>(artifacts);
    }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public boolean isScoreRequested() { return scoreRequested; }
    public void setScoreRequested(boolean scoreRequested) { this.scoreRequested = scoreRequested; }
    public Map<String, String> getQuestionDimensions() { return new LinkedHashMap<>(questionDimensions); }
    public void setQuestionDimensions(Map<String, String> questionDimensions) {
        this.questionDimensions = questionDimensions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(questionDimensions);
    }
}
