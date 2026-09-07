package com.hragent.agent.interviewer.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hragent.agent.interviewer.session.InterviewSessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ObjectMapper mapper;
    private final InterviewSessionService sessions;

    public WebSocketConfig(ObjectMapper mapper, InterviewSessionService sessions) {
        this.mapper = mapper;
        this.sessions = sessions;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new LiveInterviewWebSocketHandler(mapper, sessions), "/api/interviews/{id}/live")
                .setAllowedOriginPatterns(Arrays.stream(System.getenv()
                        .getOrDefault("HR_ALLOWED_ORIGINS", "http://localhost:*,https://localhost:*")
                        .split(",")).map(String::trim).filter(s -> !s.isBlank()).toArray(String[]::new));
    }
}
