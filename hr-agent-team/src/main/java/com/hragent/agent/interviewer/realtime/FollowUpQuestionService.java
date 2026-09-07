package com.hragent.agent.interviewer.realtime;

import com.hragent.common.DeepSeekClient;
import com.hragent.agent.interviewer.model.InterviewSession;
import com.hragent.agent.interviewer.model.InterviewStatus;
import com.hragent.agent.interviewer.session.InterviewSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class FollowUpQuestionService {
    private final InterviewSessionService sessions;
    private final DeepSeekClient client = new DeepSeekClient();
    private static final ExecutorService FOLLOW_UP_EXECUTOR = Executors.newFixedThreadPool(2);

    public FollowUpQuestionService(InterviewSessionService sessions) {
        this.sessions = sessions;
    }

    public Map<String, Object> create(String sessionId, Map<String, Object> request) {
        if (request == null) request = Map.of();
        InterviewSession session = sessions.get(sessionId);
        if (session.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有面试进行中才能生成追问");
        }
        String answerId;
        String answer;
        String parentQuestionId;
        int followUpNo;
        synchronized (session) {
            answerId = String.valueOf(request.getOrDefault("answer_id", ""));
            answer = String.valueOf(request.getOrDefault("answer", "")).trim();
            parentQuestionId = String.valueOf(request.getOrDefault("parent_question_id", ""));
            followUpNo = number(request.get("follow_up_no"));
            if (answerId.isBlank() || parentQuestionId.isBlank() || answer.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer_id、parent_question_id、answer 必填");
            }
            if (followUpNo < 1 || followUpNo > 2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每个主问题最多生成两次追问");
            }
            for (Map<String, Object> old : session.eventsSnapshot()) {
                if ("follow_up".equals(old.get("type"))
                        && answerId.equals(String.valueOf(old.get("answer_id")))
                        && followUpNo == number(old.get("follow_up_no"))) {
                    return old;
                }
            }
        }
        String question = generateQuestion(parentQuestionId, answer, followUpNo);
        synchronized (session) {
            for (Map<String, Object> old : session.eventsSnapshot()) {
                if ("follow_up".equals(old.get("type"))
                        && answerId.equals(String.valueOf(old.get("answer_id")))
                        && followUpNo == number(old.get("follow_up_no"))) {
                    return old;
                }
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "follow_up");
            event.put("answer_id", answerId);
            event.put("parent_question_id", parentQuestionId);
            event.put("follow_up_no", followUpNo);
            event.put("source", "agent");
            event.put("text", question);
            event.put("final", true);
            event.put("question_id", parentQuestionId + "-f" + followUpNo);
            sessions.appendEvent(sessionId, event);
            return event;
        }
    }

    private String generateQuestion(String parentQuestionId, String answer, int no) {
        try {
            String role = "你是线上面试追问生成器。只输出 JSON：{\\\"text\\\":\\\"...\\\"}。"
                    + "追问必须引用候选人原话，要求具体角色、约束、决策或量化结果；"
                    + "不得根据外貌、声音、情绪判断，不得暗示作弊。";
            String input = "父问题=" + parentQuestionId + "；第" + no + "次追问；候选人回答=" + answer;
            CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(
                    () -> client.callJson(role, input), FOLLOW_UP_EXECUTOR);
            Map<String, Object> result = future
                    .orTimeout(2, TimeUnit.SECONDS).exceptionally(error -> null).join();
            String text = result == null ? "" : String.valueOf(result.getOrDefault("text", "")).trim();
            if (text.length() >= 8 && text.length() <= 300) return text;
        } catch (Exception ignored) {
            // 模型不可用或超时，使用确定性模板继续面试。
        }
        return template(answer, no);
    }

    private String template(String answer, int no) {
        String preview = answer.length() > 80 ? answer.substring(0, 80) : answer;
        if (no == 1) return "你提到“" + preview + "”。请具体说明你当时承担的角色、约束和关键决策。";
        return "基于你刚才的回答，请给出一个可核验的结果指标，并说明如果重做会改变哪一步。";
    }

    private int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        FOLLOW_UP_EXECUTOR.shutdownNow();
    }
}
