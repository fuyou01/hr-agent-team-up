package com.hragent.agent.interviewer.controller;

import com.hragent.agent.interviewer.model.InterviewSession;
import com.hragent.agent.interviewer.session.InterviewSessionService;
import com.hragent.agent.interviewer.realtime.FollowUpQuestionService;
import com.hragent.agent.interviewer.media.MediaService;
import com.hragent.agent.interviewer.media.LocalVideoEventAnalyzer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewSessionService sessions;
    private final FollowUpQuestionService followUps;
    private final MediaService media;
    private final LocalVideoEventAnalyzer videoAnalyzer;

    public InterviewController(InterviewSessionService sessions, FollowUpQuestionService followUps, MediaService media,
                               LocalVideoEventAnalyzer videoAnalyzer) {
        this.sessions = sessions;
        this.followUps = followUps;
        this.media = media;
        this.videoAnalyzer = videoAnalyzer;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        InterviewSession session = sessions.create(body == null ? Map.of() : body);
        Map<String, Object> response = new LinkedHashMap<>(sessions.snapshot(session, false));
        response.put("access_token", sessions.takeCreationToken(session.getSessionId()));
        response.put("token_header", "X-Interview-Token");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) { return sessions.candidateSnapshot(sessions.get(id)); }

    @PostMapping("/{id}/live-ticket")
    public Map<String, Object> liveTicket(@PathVariable String id,
                                          @org.springframework.web.bind.annotation.RequestHeader("X-Interview-Token") String token) {
        return Map.of("ticket", sessions.issueWsTicket(id, token), "expires_in_seconds", 60);
    }

    @PostMapping("/{id}/consent")
    public Map<String, Object> consent(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return sessions.candidateSnapshot(sessions.consent(id, body));
    }

    @PostMapping("/{id}/prepare")
    public Map<String, Object> prepare(@PathVariable String id) {
        return sessions.candidateSnapshot(sessions.prepare(id));
    }

    @PostMapping("/{id}/start")
    public Map<String, Object> start(@PathVariable String id) {
        return sessions.candidateSnapshot(sessions.start(id));
    }

    @PostMapping("/{id}/events")
    public Map<String, Object> event(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (body == null || text(body.get("type")).isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "事件必须包含 type");
        }
        return sessions.candidateSnapshot(sessions.appendClientEvent(id, body));
    }

    @PostMapping("/{id}/responses")
    public Map<String, Object> response(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (body == null || text(body.get("answer_id")).isBlank()
                || (text(body.get("question_id")).isBlank() && text(body.get("question")).isBlank())
                || (text(body.get("text")).isBlank() && text(body.get("answer")).isBlank())
                || text(body.get("client_event_id")).isBlank() || !(body.get("sequence") instanceof Number)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "answer_id、question_id、text 必填");
        }
        Map<String, Object> event = new LinkedHashMap<>(body == null ? Map.of() : body);
        event.put("type", "response");
        return sessions.candidateSnapshot(sessions.appendEvent(id, event));
    }

    @PostMapping("/{id}/media/chunks")
    public Map<String, Object> mediaChunk(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return media.upload(id, body);
    }

    @GetMapping("/{id}/media/{mediaId}")
    public Map<String, Object> mediaChunks(@PathVariable String id, @PathVariable String mediaId) {
        return media.chunks(id, mediaId);
    }

    @PostMapping("/{id}/media/{mediaId}/complete")
    public Map<String, Object> completeMedia(@PathVariable String id, @PathVariable String mediaId,
                                               @RequestParam(required = false) String sha256) {
        return media.complete(id, mediaId, sha256 == null ? "" : sha256);
    }

    @PostMapping("/{id}/captions")
    public Map<String, Object> caption(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (body == null || text(body.get("utterance_id")).isBlank() || text(body.get("source")).isBlank()
                || text(body.get("text")).isBlank() || !body.containsKey("revision")
                || !(body.get("final") instanceof Boolean) || text(body.get("client_event_id")).isBlank()
                || !(body.get("sequence") instanceof Number)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "字幕必须包含 utterance_id、revision、source、text、final");
        }
        String source = text(body.get("source"));
        if (!java.util.List.of("agent", "candidate").contains(source)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "source 只能是 agent 或 candidate");
        }
        Map<String, Object> event = new LinkedHashMap<>(body);
        event.put("type", "caption");
        return sessions.candidateSnapshot(sessions.appendEvent(id, event));
    }

    @PostMapping("/{id}/video-events")
    public Map<String, Object> videoEvent(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (body == null || text(body.get("event_name")).isBlank()
                || !(body.get("confidence") instanceof Number)
                || !(body.get("review_required") instanceof Boolean)
                || text(body.get("client_event_id")).isBlank() || !(body.get("sequence") instanceof Number)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "视频事件必须包含 event_name、confidence、review_required");
        }
        String name = text(body.get("event_name"));
        if (!java.util.List.of("camera_off", "camera_blocked", "no_person_in_frame", "multiple_people_detected",
                "microphone_muted", "long_silence", "audio_video_mismatch", "tab_hidden",
                "screen_share_stopped", "possible_second_speaker").contains(name)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "不支持的视频事件类型");
        }
        double confidence = ((Number) body.get("confidence")).doubleValue();
        if (confidence < 0 || confidence > 1 || text(body.get("evidence_ref")).isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "confidence 必须为 0-1 且必须提供 evidence_ref");
        }
        Map<String, Object> event = new LinkedHashMap<>(body);
        event.put("type", "video_event");
        event.put("scoring_eligible", false);
        return sessions.candidateSnapshot(sessions.appendEvent(id, event));
    }

    @PostMapping("/{id}/video-events/analyze")
    public Map<String, Object> analyzeVideo(@PathVariable String id, @RequestBody Map<String, Object> telemetry) {
        sessions.get(id);
        java.util.List<Map<String, Object>> detected = videoAnalyzer.analyze(telemetry);
        for (Map<String, Object> event : detected) {
            Map<String, Object> stored = new LinkedHashMap<>(event);
            stored.put("type", "video_event");
            sessions.appendEvent(id, stored);
        }
        return Map.of("model_version", "local-rules-v1", "events", detected,
                "scoring_eligible", false, "review_required", true);
    }

    @PostMapping("/{id}/follow-ups")
    public Map<String, Object> followUp(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return followUps.create(id, body);
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable String id) {
        return sessions.candidateSnapshot(sessions.appendEvent(id, Map.of("type", "pause")));
    }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id) {
        return sessions.candidateSnapshot(sessions.appendEvent(id, Map.of("type", "resume")));
    }

    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@PathVariable String id) {
        return sessions.candidateSnapshot(sessions.complete(id));
    }

    @PostMapping("/{id}/score")
    public ResponseEntity<Map<String, Object>> score(@PathVariable String id) {
        InterviewSession session = sessions.scoreAsync(id);
        return ResponseEntity.accepted().body(sessions.snapshot(session, false));
    }

    @PostMapping("/{id}/review")
    public Map<String, Object> review(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return sessions.snapshot(sessions.review(id, body == null ? Map.of() : body), false);
    }

    @GetMapping("/{id}/result")
    public Map<String, Object> result(@PathVariable String id) { return sessions.result(id); }

    @GetMapping("/{id}/handoff")
    public Map<String, Object> handoff(@PathVariable String id) { return sessions.handoff(id); }

    @DeleteMapping("/{id}/media")
    public Map<String, Object> deleteMedia(@PathVariable String id) {
        media.delete(id);
        media.deleteMetadata(id);
        sessions.recordAudit(id, "media_deleted", Map.of());
        return Map.of("ok", true, "session_id", id, "message", "媒体及其物理分片已删除");
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
