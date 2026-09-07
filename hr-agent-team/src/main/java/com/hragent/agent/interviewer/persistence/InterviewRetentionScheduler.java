package com.hragent.agent.interviewer.persistence;

import com.hragent.agent.interviewer.media.MediaService;
import com.hragent.agent.interviewer.session.InterviewSessionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 默认七天清理媒体、字幕、事件和会话快照；失败隔离，避免一条脏数据阻塞全量清理。 */
@Component
public class InterviewRetentionScheduler {
    private final InterviewSessionRepository repository;
    private final MediaService media;
    private final InterviewSessionService sessions;
    private final long retentionDays;

    public InterviewRetentionScheduler(InterviewSessionRepository repository, MediaService media,
                                       InterviewSessionService sessions) {
        this.repository = repository;
        this.media = media;
        this.sessions = sessions;
        this.retentionDays = Long.parseLong(System.getenv().getOrDefault("HR_MEDIA_RETENTION_DAYS", "7"));
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(Math.max(1, retentionDays), ChronoUnit.DAYS);
        for (String sessionId : repository.findExpiredSessionIds(cutoff)) {
            boolean physicalDeleted = true;
            try { media.deletePhysical(sessionId); } catch (RuntimeException ignored) { physicalDeleted = false; }
            if (physicalDeleted) {
                try { repository.deleteSessionData(sessionId); sessions.evict(sessionId); }
                catch (RuntimeException ignored) { }
            }
        }
    }
}
