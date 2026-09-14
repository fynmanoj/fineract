package org.apache.fineract.infrastructure.security.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.config.FineractProperties.FineractSecurityProperties;
import org.apache.fineract.infrastructure.core.config.FineractProperties.FineractSecuritySession;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.domain.UserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;

@ExtendWith(MockitoExtension.class)
public class PurgeExpiredUserSessionsTaskletTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private StepContribution stepContribution;
    @Mock
    private ChunkContext chunkContext;

    private PurgeExpiredUserSessionsTasklet underTest;

    @BeforeEach
    public void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.now(ZoneId.systemDefault()))));

        final FineractProperties fineractProperties = new FineractProperties();
        final FineractSecurityProperties security = new FineractSecurityProperties();
        final FineractSecuritySession session = new FineractSecuritySession();
        session.setIdleTimeoutMinutes(15);
        security.setSession(session);
        fineractProperties.setSecurity(security);

        underTest = new PurgeExpiredUserSessionsTasklet(userSessionRepository, fineractProperties);
    }

    @Test
    public void executePurgesExpiredAndIdleSessions() {
        final RepeatStatus status = underTest.execute(stepContribution, chunkContext);

        assertEquals(RepeatStatus.FINISHED, status);
        final ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userSessionRepository).deleteExpiredHardExpirySessions(org.mockito.ArgumentMatchers.any(LocalDateTime.class));
        verify(userSessionRepository).deleteIdleSlidingSessions(cutoffCaptor.capture());
        final LocalDateTime expectedCutoff = DateUtils.getLocalDateTimeOfTenant().minusMinutes(15);
        assertTrue(cutoffCaptor.getValue().isAfter(expectedCutoff.minusSeconds(2)));
        assertTrue(cutoffCaptor.getValue().isBefore(expectedCutoff.plusSeconds(2)));
    }
}
