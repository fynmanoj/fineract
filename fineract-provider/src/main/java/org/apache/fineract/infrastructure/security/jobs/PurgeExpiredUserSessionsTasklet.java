package org.apache.fineract.infrastructure.security.jobs;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.domain.UserSessionRepository;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PurgeExpiredUserSessionsTasklet implements Tasklet {

    private final UserSessionRepository userSessionRepository;
    private final FineractProperties fineractProperties;

    @Override
    @Transactional
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        try {
            final LocalDateTime now = DateUtils.getLocalDateTimeOfTenant();
            final int idleTimeoutMinutes = resolveIdleTimeoutMinutes();
            final LocalDateTime idleCutoff = now.minusMinutes(idleTimeoutMinutes);
            userSessionRepository.deleteExpiredHardExpirySessions(now);
            userSessionRepository.deleteIdleSlidingSessions(idleCutoff);
        } catch (Exception e) {
            log.error("Error occurred while purging expired user sessions: ", e);
        }
        return RepeatStatus.FINISHED;
    }

    private int resolveIdleTimeoutMinutes() {
        if (fineractProperties.getSecurity() != null && fineractProperties.getSecurity().getSession() != null) {
            return fineractProperties.getSecurity().getSession().getIdleTimeoutMinutes();
        }
        return 10;
    }
}
