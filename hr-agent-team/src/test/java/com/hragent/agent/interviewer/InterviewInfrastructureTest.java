package com.hragent.agent.interviewer;

import com.hragent.agent.interviewer.artifact.ArtifactService;
import com.hragent.agent.interviewer.model.InterviewStatus;
import com.hragent.agent.interviewer.prompt.IndustryProfileService;
import com.hragent.agent.interviewer.prompt.PromptPackService;
import com.hragent.agent.interviewer.scoring.InterviewScoringService;
import com.hragent.agent.interviewer.session.InterviewSessionService;
import com.hragent.agent.interviewer.media.LocalVideoEventAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InterviewInfrastructureTest {
    private InterviewSessionService service() {
        return new InterviewSessionService(
                new PromptPackService(new IndustryProfileService()),
                new InterviewScoringService(), new ArtifactService());
    }

    private String readySession(InterviewSessionService service) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidate", Map.of("candidate_id", "C-1", "name", "测试"));
        body.put("jd", Map.of("title", "后端工程师"));
        body.put("industry", "unknown");
        String id = service.create(body).getSessionId();
        service.consent(id, Map.of("camera", "denied", "microphone", "granted", "recording_mode", "none"));
        service.prepare(id);
        service.start(id);
        return id;
    }

    @Test
    void unknownIndustryFallsBackToComputer() {
        InterviewSessionService service = service();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidate", Map.of("name", "测试"));
        body.put("jd", Map.of("title", "工程师"));
        body.put("industry", "not-supported");
        var session = service.create(body);
        assertEquals("computer", session.getIndustry());
        assertTrue(session.isIndustryDefaulted());
    }

    @Test
    void scoringAllowsMissingEvidenceAndWaitsForReview() {
        InterviewSessionService service = service();
        String id = readySession(service);
        service.appendEvent(id, Map.of("type", "response", "answer_id", "A-1",
                "question_id", "Q-1", "text", "我负责过一个项目。", "final", true));
        service.complete(id);
        var scored = service.score(id);
        assertEquals(InterviewStatus.HUMAN_REVIEW, scored.getStatus());
        assertNull(scored.getScorecard().get("overall_score"));
        assertEquals("pending", scored.getReviewStatus());
    }

    @Test
    void duplicateClientEventIsIdempotent() {
        InterviewSessionService service = service();
        String id = readySession(service);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "response");
        event.put("answer_id", "A-1");
        event.put("question_id", "Q-1");
        event.put("text", "答案");
        event.put("client_event_id", "evt-1");
        event.put("sequence", 1);
        service.appendEvent(id, event);
        service.appendEvent(id, event);
        assertEquals(1, service.get(id).responsesSnapshot().size());
    }

    @Test
    void genericClientEventCannotBypassSchemaValidation() {
        InterviewSessionService service = service();
        String id = readySession(service);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.appendClientEvent(id, Map.of("type", "video_event", "severity", "high")));
    }

    @Test
    void sessionTokenIsIssuedOnceAndCanBeVerified() {
        InterviewSessionService service = service();
        var session = service.create(Map.of("candidate", Map.of("name", "测试"), "jd", Map.of("title", "工程师")));
        String token = service.takeCreationToken(session.getSessionId());
        assertNotNull(token);
        assertTrue(service.verifyToken(session.getSessionId(), token));
        assertNull(service.takeCreationToken(session.getSessionId()));
    }

    @Test
    void candidateSnapshotDoesNotExposeAssessmentData() {
        InterviewSessionService service = service();
        var session = service.create(Map.of("candidate", Map.of("name", "测试"), "jd", Map.of("title", "工程师")));
        var snapshot = service.candidateSnapshot(session);
        assertFalse(snapshot.containsKey("scorecard"));
        assertFalse(snapshot.containsKey("minutes"));
        assertFalse(snapshot.containsKey("events"));
        assertFalse(snapshot.containsKey("artifacts"));
    }

    @Test
    void localVideoAnalyzerOnlyEmitsTechnicalRiskSignals() {
        var events = new LocalVideoEventAnalyzer().analyze(Map.of(
                "camera_active", true, "person_count", 2, "tab_visible", false));
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(e -> Boolean.FALSE.equals(e.get("scoring_eligible"))));
        assertTrue(events.stream().noneMatch(e -> e.containsKey("face_embedding") || e.containsKey("emotion")));
    }
}
