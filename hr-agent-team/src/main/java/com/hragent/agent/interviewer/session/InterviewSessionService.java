package com.hragent.agent.interviewer.session;

import com.hragent.agent.interviewer.InterviewerAgent;
import com.hragent.agent.interviewer.model.InterviewSession;
import com.hragent.agent.interviewer.model.InterviewStatus;
import com.hragent.agent.interviewer.prompt.PromptPackService;
import com.hragent.agent.interviewer.scoring.InterviewScoringService;
import com.hragent.agent.interviewer.artifact.ArtifactService;
import com.hragent.agent.assessor.AssessorAgent;
import com.hragent.agent.interviewer.persistence.InterviewSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class InterviewSessionService {
    private final Map<String, InterviewSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> creationTokens = new ConcurrentHashMap<>();
    private final Map<String, WsTicket> wsTickets = new ConcurrentHashMap<>();
    private final PromptPackService promptPacks;
    private final InterviewScoringService scoring;
    private final ArtifactService artifacts;
    private final InterviewerAgent interviewer = new InterviewerAgent();
    private final AssessorAgent assessor = new AssessorAgent();
    private final ExecutorService scoreExecutor = Executors.newFixedThreadPool(2);
    private final InterviewSessionRepository repository;

    public InterviewSessionService(PromptPackService promptPacks, InterviewScoringService scoring, ArtifactService artifacts) {
        this(promptPacks, scoring, artifacts, new com.hragent.agent.interviewer.persistence.NoopInterviewSessionRepository());
    }

    @Autowired
    public InterviewSessionService(PromptPackService promptPacks, InterviewScoringService scoring, ArtifactService artifacts,
                                    InterviewSessionRepository repository) {
        this.promptPacks = promptPacks;
        this.scoring = scoring;
        this.artifacts = artifacts;
        this.repository = repository;
    }

    public InterviewSession create(Map<String, Object> body) {
        if (body == null || map(body, "candidate").isEmpty() || map(body, "jd").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "candidate 和 jd 必填");
        }
        String requested = value(body, "industry");
        String industry = promptPacksIndustry(requested);
        boolean defaulted = requested.isBlank() || !industry.equals(requested);
        InterviewSession session = new InterviewSession(
                "S-" + UUID.randomUUID(), map(body, "candidate"), map(body, "jd"), map(body, "resume"),
                bool(body.get("online"), true), industry, defaulted);
        String accessToken = generateToken();
        session.setAccessTokenHash(hashToken(accessToken));
        sessions.put(session.getSessionId(), session);
        creationTokens.put(session.getSessionId(), accessToken);
        persist(session);
        return session;
    }

    /** 仅创建响应读取一次明文 token；数据库只保存哈希。 */
    public String takeCreationToken(String id) { return creationTokens.remove(id); }

    public void evict(String id) { sessions.remove(id); creationTokens.remove(id); }

    public String issueWsTicket(String id, String accessToken) {
        if (!verifyToken(id, accessToken)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话 token 无效");
        String ticket = generateToken();
        wsTickets.put(ticket, new WsTicket(id, Instant.now().plusSeconds(60)));
        return ticket;
    }

    public boolean consumeWsTicket(String id, String ticket) {
        if (ticket == null || ticket.isBlank()) return false;
        WsTicket value = wsTickets.remove(ticket);
        return value != null && value.sessionId().equals(id) && value.expiresAt().isAfter(Instant.now());
    }

    private record WsTicket(String sessionId, Instant expiresAt) { }

    private String promptPacksIndustry(String requested) {
        return List.of("computer", "sales", "finance", "legal", "manufacturing", "supply_chain", "design", "marketing")
                .contains(requested) ? requested : "computer";
    }

    public InterviewSession get(String id) {
        InterviewSession session = sessions.get(id);
        if (session == null) {
            session = repository.find(id).orElse(null);
            if (session != null) sessions.put(id, session);
        }
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知面试会话：" + id);
        return session;
    }

    public synchronized InterviewSession consent(String id, Map<String, Object> body) {
        InterviewSession session = get(id);
        require(session, InterviewStatus.INITIALIZED);
        if (body == null) body = Map.of();
        String camera = value(body, "camera", "denied");
        String microphone = value(body, "microphone", "denied");
        String recordingMode = value(body, "recording_mode", "none");
        if (!List.of("granted", "denied", "unavailable").contains(camera)
                || !List.of("granted", "denied", "unavailable").contains(microphone)
                || !List.of("none", "temporary", "retained").contains(recordingMode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "授权字段值非法");
        }
        session.setCamera(camera);
        session.setMicrophone(microphone);
        session.setRecordingMode(recordingMode);
        session.appendEvent(audit("consent", body));
        session.setStatus(InterviewStatus.CONSENTED);
        persist(session);
        return session;
    }

    public InterviewSession prepare(String id) {
        InterviewSession session = get(id);
        Map<String, Object> planInput = new LinkedHashMap<>();
        synchronized (session) {
            require(session, InterviewStatus.CONSENTED);
            session.setPromptPack(promptPacks.build(session.getIndustry(), session.getJd(), session.getResume()));
            session.setStatus(InterviewStatus.PROMPT_READY);
            planInput.put("jd", session.getJd());
            planInput.put("resume", session.getResume());
            planInput.put("promptPack", session.getPromptPack());
        }
        Map<String, Object> result;
        try {
            result = interviewer.run(planInput);
        } catch (Exception ignored) {
            result = fallbackPlan(session.getPromptPack());
        }
        if (!validPlan(result)) result = fallbackPlan(session.getPromptPack());
        synchronized (session) {
            require(session, InterviewStatus.PROMPT_READY);
            session.setPlan(mapAny(result, "plan"));
            session.setQuestionDimensions(assignQuestionDimensions(session.getPlan(), session.getPromptPack()));
            session.setArtifacts(artifacts.writePlan(session.getSessionId(), session.getPlan()));
            session.appendEvent(audit("plan_ready", Map.of("prompt_pack_version", session.getPromptPack().get("prompt_pack_version"))));
            session.setStatus(InterviewStatus.PLAN_READY);
            persist(session);
        }
        return session;
    }

    public synchronized InterviewSession start(String id) {
        InterviewSession session = get(id);
        require(session, InterviewStatus.PLAN_READY);
        session.appendEvent(audit("device_checked", Map.of("camera", session.getCamera(), "microphone", session.getMicrophone())));
        session.setStatus(InterviewStatus.DEVICE_CHECKED);
        session.setStatus(InterviewStatus.IN_PROGRESS);
        persist(session);
        return session;
    }

    public InterviewSession appendEvent(String id, Map<String, Object> body) {
        InterviewSession session = get(id);
        synchronized (session) {
            if (session.getStatus() != InterviewStatus.IN_PROGRESS && session.getStatus() != InterviewStatus.PAUSED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不接收实时事件：" + session.getStatus());
            }
            Map<String, Object> event;
            try {
                event = session.appendEvent(body);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
            }
            if ("pause".equals(event.get("type"))) session.setStatus(InterviewStatus.PAUSED);
            if ("resume".equals(event.get("type"))) session.setStatus(InterviewStatus.IN_PROGRESS);
            persist(session);
            return session;
        }
    }

    public InterviewSession appendClientEvent(String id, Map<String, Object> body) {
        validateClientEvent(body);
        return appendEvent(id, body);
    }

    private void validateClientEvent(Map<String, Object> body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件不能为空");
        String type = value(body, "type");
        if (!List.of("response", "caption", "video_event", "pause", "resume", "device_event").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不允许的客户端事件类型");
        }
        if (value(body, "client_event_id").isBlank() || !(body.get("sequence") instanceof Number)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户端事件必须包含 client_event_id 和 sequence");
        }
        if ("response".equals(type) && (value(body, "answer_id").isBlank()
                || (value(body, "question_id").isBlank() && value(body, "question").isBlank())
                || (value(body, "text").isBlank() && value(body, "answer").isBlank()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "response 事件字段不完整");
        }
        if ("caption".equals(type) && (value(body, "utterance_id").isBlank() || value(body, "source").isBlank()
                || value(body, "text").isBlank() || !body.containsKey("revision") || !(body.get("final") instanceof Boolean))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "caption 事件字段不完整");
        }
        if ("video_event".equals(type)) {
            Object confidence = body.get("confidence");
            if (!(confidence instanceof Number) || ((Number) confidence).doubleValue() < 0
                    || ((Number) confidence).doubleValue() > 1 || value(body, "event_name").isBlank()
                    || value(body, "evidence_ref").isBlank() || !(body.get("review_required") instanceof Boolean)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "video_event 事件字段不完整");
            }
            body.put("scoring_eligible", false);
        }
    }

    public synchronized InterviewSession complete(String id) {
        InterviewSession session = get(id);
        if (session.getStatus() != InterviewStatus.IN_PROGRESS && session.getStatus() != InterviewStatus.PAUSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能从面试进行中提交：" + session.getStatus());
        }
        session.appendEvent(audit("submitted", Map.of()));
        session.setStatus(InterviewStatus.SUBMITTED);
        persist(session);
        return session;
    }

    public InterviewSession score(String id) {
        InterviewSession session = get(id);
        Map<String, Object> transcriptInput = new LinkedHashMap<>();
        synchronized (session) {
            require(session, InterviewStatus.SUBMITTED);
            transcriptInput.put("jd", session.getJd());
            transcriptInput.put("resume", session.getResume());
            transcriptInput.put("plan", session.getPlan());
            transcriptInput.put("transcript", transcript(session.responsesSnapshot()));
        }
        Map<String, Object> result;
        try {
            result = interviewer.run(transcriptInput);
        } catch (Exception ignored) {
            result = fallbackMinutes(session.responsesSnapshot());
        }
        if (!validMinutes(result)) result = fallbackMinutes(session.responsesSnapshot());
        synchronized (session) {
            require(session, InterviewStatus.SUBMITTED);
            session.setMinutes(mapAny(result, "minutes"));
            session.setStatus(InterviewStatus.MINUTES_READY);
            session.setScorecard(scoring.score(session.getMinutes(), session.getPromptPack(), session.getQuestionDimensions()));
            session.setArtifacts(mergeArtifacts(session.getArtifacts(), artifacts.writeMinutes(session.getSessionId(), session.getMinutes(), session.getScorecard())));
            session.setStatus(InterviewStatus.SCORED);
            session.setReviewStatus("pending");
            Map<String, Object> scoreAudit = new LinkedHashMap<>();
            scoreAudit.put("overall_score", session.getScorecard().get("overall_score"));
            session.appendEvent(audit("scored", scoreAudit));
            session.setStatus(InterviewStatus.HUMAN_REVIEW);
            persist(session);
        }
        return session;
    }

    public InterviewSession scoreAsync(String id) {
        InterviewSession session = get(id);
        synchronized (session) {
            require(session, InterviewStatus.SUBMITTED);
            if (session.isScoreRequested()) return session;
            session.setScoreRequested(true);
            persist(session);
        }
        scoreExecutor.submit(() -> {
            try {
                score(id);
            } catch (Exception e) {
                synchronized (session) {
                    session.setScoreRequested(false);
                    session.setStatus(InterviewStatus.FAILED);
                    session.setReviewStatus("failed");
                    persist(session);
                }
            }
        });
        return session;
    }

    public synchronized InterviewSession review(String id, Map<String, Object> body) {
        InterviewSession session = get(id);
        require(session, InterviewStatus.HUMAN_REVIEW);
        if (body == null) body = Map.of();
        String action = value(body, "action", "approve");
        if (!List.of("approve", "reject", "escalate").contains(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法人工复核动作：" + action);
        }
        if ("approve".equals(action) && hasHighRisk(session.eventsSnapshot())) {
            String disposition = value(body, "risk_disposition");
            if (!List.of("accepted", "mitigated", "not_reproducible").contains(disposition)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "存在高严重度技术风险信号，需先提供 risk_disposition");
            }
        }
        if ("reject".equals(action) || "escalate".equals(action)) {
            session.appendEvent(audit("human_review", reviewAudit(body, session, null)));
            session.setReviewStatus("escalated");
            session.setStatus(InterviewStatus.ESCALATED);
            persist(session);
            return session;
        }
        Map<String, Object> scorecard = new LinkedHashMap<>(session.getScorecard());
        Object finalScore = body.get("final_score");
        if (!(finalScore instanceof Number)) finalScore = scorecard.get("overall_score");
        if (!(finalScore instanceof Number) || ((Number) finalScore).doubleValue() % 1 != 0
                || ((Number) finalScore).intValue() < 0 || ((Number) finalScore).intValue() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "final_score 必须是 0-100");
        }
        session.appendEvent(audit("human_review", reviewAudit(body, session, finalScore)));
        scorecard.put("final_score", finalScore);
        scorecard.put("review_status", "approved");
        scorecard.put("reviewer_id", value(body, "reviewer_id", "unknown"));
        scorecard.put("review_reason", value(body, "reason", "人工确认").toString());
        session.setScorecard(scorecard);
        session.setReviewStatus("approved");
        session.setHandoff(buildHandoff(session));
        session.setStatus(InterviewStatus.DELIVERED);
        persist(session);
        dispatchToAssessor(session);
        return session;
    }

    private Map<String, Object> reviewAudit(Map<String, Object> body, InterviewSession session, Object newScore) {
        Map<String, Object> reviewAudit = new LinkedHashMap<>(body);
        reviewAudit.put("original_score", session.getScorecard().get("overall_score"));
        reviewAudit.put("reviewer_id", value(body, "reviewer_id", "unknown"));
        reviewAudit.put("reason", value(body, "reason", "人工确认"));
        if (newScore != null) reviewAudit.put("new_score", newScore);
        return reviewAudit;
    }

    private boolean hasHighRisk(List<Map<String, Object>> events) {
        for (Map<String, Object> event : events) {
            if (("video_event".equals(event.get("type")) || "risk_signal".equals(event.get("type")))
                    && "high".equals(String.valueOf(event.get("severity")))) return true;
        }
        return false;
    }

    public Map<String, Object> result(String id) {
        InterviewSession s = get(id);
        return snapshot(s, false);
    }

    public Map<String, Object> candidateSnapshot(InterviewSession s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "interview-session-v1");
        out.put("session_id", s.getSessionId());
        out.put("request_id", s.getSessionId());
        out.put("status", s.getStatus());
        out.put("industry", s.getIndustry());
        out.put("industry_defaulted", s.isIndustryDefaulted());
        out.put("candidate", s.getCandidate());
        out.put("prompt_pack", s.getPromptPack());
        out.put("plan", s.getPlan());
        return out;
    }

    public Map<String, Object> handoff(String id) {
        InterviewSession s = get(id);
        if (s.getStatus() != InterviewStatus.DELIVERED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "人工复核完成后才能交接 D");
        }
        return s.getHandoff();
    }

    public void recordAudit(String id, String type, Map<String, Object> payload) {
        InterviewSession session = get(id);
        synchronized (session) {
            session.appendEvent(audit(type, payload));
            persist(session);
        }
    }

    public boolean verifyToken(String id, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return false;
        InterviewSession session = get(id);
        return MessageDigest.isEqual(session.getAccessTokenHash().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                hashToken(rawToken).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) { throw new IllegalStateException("无法生成访问凭证", e); }
    }

    private void persist(InterviewSession session) {
        repository.save(session);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingScores() {
        for (InterviewSession session : repository.findAll()) {
            if (session.getStatus() == InterviewStatus.SUBMITTED && session.isScoreRequested()) {
                synchronized (session) { session.setScoreRequested(false); persist(session); }
                scoreAsync(session.getSessionId());
            }
        }
    }

    public Map<String, Object> snapshot(InterviewSession s, boolean includeEvents) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "interview-session-v1");
        out.put("session_id", s.getSessionId());
        out.put("request_id", s.getSessionId());
        out.put("status", s.getStatus());
        out.put("industry", s.getIndustry());
        out.put("industry_defaulted", s.isIndustryDefaulted());
        out.put("candidate", s.getCandidate());
        out.put("prompt_pack", s.getPromptPack());
        out.put("plan", s.getPlan());
        out.put("minutes", s.getMinutes());
        out.put("scorecard", s.getScorecard());
        out.put("review_status", s.getReviewStatus());
        out.put("artifacts", s.getArtifacts());
        if (includeEvents) out.put("events", s.eventsSnapshot());
        return out;
    }

    private Map<String, Object> buildHandoff(InterviewSession s) {
        Map<String, Object> handoff = new LinkedHashMap<>();
        handoff.put("schema_version", "interview-handoff-v1");
        handoff.put("session_id", s.getSessionId());
        handoff.put("candidate", s.getCandidate());
        handoff.put("industry", s.getIndustry());
        handoff.put("jd", s.getJd());
        handoff.put("resume", s.getResume());
        handoff.put("minutes", s.getMinutes());
        handoff.put("scorecard", s.getScorecard());
        handoff.put("risk_signals", riskSignals(s.eventsSnapshot()));
        handoff.put("artifacts", s.getArtifacts());
        handoff.put("review_status", "approved");
        handoff.put("handoff_version", 1);
        handoff.put("downstream_agent", "assessor");
        handoff.put("downstream_status", "dispatch_pending");
        return handoff;
    }

    private void dispatchToAssessor(InterviewSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jd", session.getJd());
        payload.put("resume", session.getResume());
        payload.put("minutes", session.getMinutes());
        payload.put("scorecard", session.getScorecard());
        scoreExecutor.submit(() -> {
            try {
                Map<String, Object> result = assessor.run(payload);
                synchronized (session) {
                    Map<String, Object> handoff = new LinkedHashMap<>(session.getHandoff());
                    handoff.put("downstream_status", "accepted");
                    handoff.put("downstream_result", result);
                    session.setHandoff(handoff);
                    session.appendEvent(audit("handoff_dispatched", Map.of("agent", "assessor", "status", "accepted")));
                    persist(session);
                }
            } catch (Exception e) {
                synchronized (session) {
                    Map<String, Object> handoff = new LinkedHashMap<>(session.getHandoff());
                    handoff.put("downstream_status", "failed");
                    handoff.put("downstream_error", e.getClass().getSimpleName());
                    session.setHandoff(handoff);
                    session.appendEvent(audit("handoff_dispatched", Map.of("agent", "assessor", "status", "failed")));
                    persist(session);
                }
            }
        });
    }

    private Map<String, String> assignQuestionDimensions(Map<String, Object> plan,
                                                          Map<String, Object> promptPack) {
        List<Map<String, Object>> questions = maps(plan.get("questions"));
        List<Map<String, Object>> rubric = maps(promptPack.get("rubric"));
        Map<String, String> result = new LinkedHashMap<>();
        if (rubric.isEmpty()) return result;
        for (int i = 0; i < questions.size(); i++) {
            String question = String.valueOf(questions.get(i).getOrDefault("text", ""));
            String intent = String.valueOf(questions.get(i).getOrDefault("intent", ""));
            String dimension = null;
            for (Map<String, Object> rule : rubric) {
                String candidate = String.valueOf(rule.getOrDefault("dimension", ""));
                if (!candidate.isBlank() && (intent.contains(candidate) || question.contains(candidate))) {
                    dimension = candidate;
                    break;
                }
            }
            if (dimension == null) {
                dimension = String.valueOf(rubric.get(i % rubric.size()).getOrDefault("dimension", "综合能力"));
            }
            result.put(question, dimension);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }

    private List<Map<String, Object>> riskSignals(List<Map<String, Object>> events) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> event : events) {
            if ("video_event".equals(event.get("type")) || "risk_signal".equals(event.get("type"))) result.add(event);
        }
        return result;
    }

    private List<Map<String, Object>> mergeArtifacts(List<Map<String, Object>> first, List<Map<String, Object>> second) {
        List<Map<String, Object>> merged = new ArrayList<>();
        if (first != null) merged.addAll(first);
        if (second != null) merged.addAll(second);
        return merged;
    }

    private Map<String, Object> fallbackPlan(Map<String, Object> pack) {
        Map<String, Object> plan = new LinkedHashMap<>();
        List<Map<String, Object>> questions = new ArrayList<>();
        questions.add(question("[通用素质｜限时2分钟] 请概括三项可迁移能力，并说明如何解决本岗位最大痛点。", "考察自我认知和岗位匹配，要求具体事实。"));
        questions.add(question("[抗压] 请描述一次资源极度匮乏仍完成目标的经历。", "考察资源调配、复原力和量化结果。"));
        questions.add(question("[线上协作] 同事两天不回复且 deadline 临近，你如何升级？", "考察异步沟通和自我管理。"));
        questions.add(question("[项目深挖] 请讲一个你负责的复杂项目，并说明目标、角色、约束、决策和结果。", "为后续引用式追问建立项目事实。"));
        questions.add(question("[反向提问] 关于岗位前三个月，最大的非技术性挑战是什么？", "考察业务理解和提问质量。"));
        plan.put("questions", questions);
        plan.put("rubric", pack.getOrDefault("rubric", List.of()));
        return new LinkedHashMap<>(Map.of("plan", plan, "reason", "模板降级：结论→依据→风险→下一步"));
    }

    private Map<String, Object> fallbackMinutes(List<Map<String, Object>> responses) {
        List<Map<String, Object>> qa = new ArrayList<>();
        for (Map<String, Object> response : responses) {
            String answer = String.valueOf(response.getOrDefault("text", response.getOrDefault("answer", "")));
            qa.add(new LinkedHashMap<>(Map.of(
                    "question", response.getOrDefault("question", response.getOrDefault("question_id", "unknown")),
                    "answer", answer, "score", answer.isBlank() ? 0 : 50)));
        }
        return new LinkedHashMap<>(Map.of("minutes", Map.of(
                "summary", "模型不可用，已保留原始回答并转人工复核。",
                "qa", qa, "verdict", "hold"),
                "reason", "降级：结论→依据→风险→下一步"));
    }

    @SuppressWarnings("unchecked")
    private boolean validPlan(Map<String, Object> result) {
        if (result == null || !(result.get("plan") instanceof Map<?, ?> raw)) return false;
        Object questions = raw.get("questions");
        Object rubric = raw.get("rubric");
        if (!(questions instanceof List<?> q) || q.isEmpty() || !(rubric instanceof List<?> r) || r.isEmpty()) return false;
        for (Object item : q) {
            if (!(item instanceof Map<?, ?> map) || text(map, "text").isBlank()
                    || text(map, "intent").isBlank()) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean validMinutes(Map<String, Object> result) {
        if (result == null || !(result.get("minutes") instanceof Map<?, ?> raw)) return false;
        if (text(raw, "summary").isBlank()) return false;
        if (!(raw.get("qa") instanceof List<?> qa)) return false;
        String verdict = text(raw, "verdict");
        if (!List.of("shortlist", "hold", "reject").contains(verdict)) return false;
        for (Object item : qa) {
            if (!(item instanceof Map<?, ?> map) || !(map.get("score") instanceof Number)
                    || text(map, "question").isBlank()) return false;
            int score = ((Number) map.get("score")).intValue();
            if (((Number) map.get("score")).doubleValue() % 1 != 0
                    || score < 0 || score > 100 || text(map, "answer").isBlank()) return false;
        }
        return true;
    }

    private String transcript(List<Map<String, Object>> responses) {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> response : responses) {
            text.append("Q:").append(response.getOrDefault("question", response.getOrDefault("question_id", "")))
                    .append("\nA:").append(response.getOrDefault("text", response.getOrDefault("answer", ""))).append("\n");
        }
        return text.toString();
    }

    private Map<String, Object> question(String text, String intent) {
        return new LinkedHashMap<>(Map.of("text", text, "intent", intent));
    }

    private void require(InterviewSession s, InterviewStatus expected) {
        if (s.getStatus() != expected) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "当前状态为 " + s.getStatus() + "，要求 " + expected);
    }

    private Map<String, Object> audit(String type, Map<String, Object> body) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("payload", body == null ? Map.of() : body);
        event.put("occurred_at", Instant.now().toString());
        return event;
    }

    private String value(Map<String, Object> body, String key) { return value(body, key, ""); }
    private String value(Map<String, Object> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value instanceof Map<?, ?> ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapAny(Map<?, ?> body, String key) {
        Object value = body.get(key);
        return value instanceof Map<?, ?> ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }

    private boolean bool(Object value, boolean fallback) { return value instanceof Boolean ? (Boolean) value : fallback; }

    private String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        scoreExecutor.shutdown();
        try {
            scoreExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
