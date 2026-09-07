package com.hragent.agent.interviewer.security;

import com.hragent.agent.interviewer.session.InterviewSessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcSecurityConfig implements WebMvcConfigurer {
    private final InterviewSessionService sessions;
    public WebMvcSecurityConfig(InterviewSessionService sessions) { this.sessions = sessions; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new InterviewAuthInterceptor(sessions)).addPathPatterns("/api/interviews/**");
    }
}
