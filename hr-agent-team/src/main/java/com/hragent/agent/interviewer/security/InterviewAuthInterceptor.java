package com.hragent.agent.interviewer.security;

import com.hragent.agent.interviewer.session.InterviewSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 会话级 token 鉴权；创建接口不需要 token，token 只在创建响应中明文返回一次。 */
public class InterviewAuthInterceptor implements HandlerInterceptor {
    private static final Pattern PATH = Pattern.compile("^/api/interviews/([^/]+)(?:/.*)?$");
    private final InterviewSessionService sessions;
    private final boolean required;

    public InterviewAuthInterceptor(InterviewSessionService sessions) {
        this.sessions = sessions;
        this.required = Boolean.parseBoolean(System.getenv().getOrDefault("HR_REQUIRE_AUTH", "true"));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!required || ("POST".equalsIgnoreCase(request.getMethod()) && "/api/interviews".equals(request.getRequestURI()))) return true;
        Matcher matcher = PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) return true;
        if (requiresReviewer(request)) {
            String reviewer = System.getenv().getOrDefault("HR_REVIEWER_TOKEN", "");
            String supplied = request.getHeader("X-Reviewer-Token");
            if (!reviewer.isBlank() && supplied != null && MessageDigest.isEqual(
                    reviewer.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) return true;
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        String token = request.getHeader("X-Interview-Token");
        try {
            if (sessions.verifyToken(matcher.group(1), token)) {
                return true;
            }
        } catch (RuntimeException ignored) { }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer realm=interview");
        return false;
    }

    private boolean requiresReviewer(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.endsWith("/review") || path.endsWith("/result") || path.endsWith("/handoff")
                || path.endsWith("/score") || path.endsWith("/media")) return true;
        return "GET".equalsIgnoreCase(request.getMethod()) && path.matches(".*/media/[^/]+$");
    }
}
