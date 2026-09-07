package com.hragent.agent.interviewer.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hragent.agent.interviewer.session.InterviewSessionService;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.net.URI;

public class LiveInterviewWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper mapper;
    private final InterviewSessionService sessions;

    public LiveInterviewWebSocketHandler(ObjectMapper mapper, InterviewSessionService sessions) {
        this.mapper = mapper;
        this.sessions = sessions;
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        Map<String, Object> event = mapper.readValue(message.getPayload(), new TypeReference<>() {});
        String sessionId = String.valueOf(event.getOrDefault("session_id", ""));
        if (sessionId.isBlank()) throw new IllegalArgumentException("实时事件缺少 session_id");
        URI uri = socket.getUri();
        String pathSessionId = uri == null ? "" : uri.getPath().replaceFirst(".*/api/interviews/", "").replaceFirst("/live$", "");
        if (!sessionId.equals(pathSessionId)) throw new IllegalArgumentException("WebSocket 会话与事件 session_id 不一致");
        String ticket = socket.getHandshakeHeaders().getFirst("X-Interview-WS-Ticket");
        if ((ticket == null || ticket.isBlank()) && uri != null && uri.getQuery() != null) {
            for (String pair : uri.getQuery().split("&")) {
                if (pair.startsWith("ticket=")) ticket = java.net.URLDecoder.decode(pair.substring(7), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        boolean authRequired = Boolean.parseBoolean(System.getenv().getOrDefault("HR_REQUIRE_AUTH", "true"));
        if (authRequired && !Boolean.TRUE.equals(socket.getAttributes().get("interview.authenticated"))) {
            if (!sessions.consumeWsTicket(sessionId, ticket)) throw new IllegalArgumentException("WebSocket ticket 无效");
            socket.getAttributes().put("interview.authenticated", true);
        }
        sessions.appendClientEvent(sessionId, event);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "ack");
        ack.put("session_id", sessionId);
        ack.put("client_event_id", event.get("client_event_id"));
        ack.put("accepted", true);
        socket.sendMessage(new TextMessage(mapper.writeValueAsString(ack)));
    }
}
