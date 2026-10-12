package org.apache.fineract.infrastructure.security.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.config.FineractProperties.FineractSecurityProperties;
import org.apache.fineract.infrastructure.core.config.FineractProperties.FineractSecuritySession;
import org.apache.fineract.infrastructure.core.config.FineractProperties.FineractSecuritySystemUser;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.domain.PlatformUserRepository;
import org.apache.fineract.infrastructure.security.domain.SessionPolicy;
import org.apache.fineract.infrastructure.security.domain.UserSession;
import org.apache.fineract.infrastructure.security.domain.UserSessionRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;

@ExtendWith(MockitoExtension.class)
public class SessionHandlerServiceImplTest {

    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private AccessTokenGenerationService accessTokenGenerationService;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private PlatformUserRepository platformUserRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private FineractProperties fineractProperties;
    private SessionHandlerServiceImpl underTest;

    @BeforeEach
    public void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.now(ZoneId.systemDefault()))));

        fineractProperties = new FineractProperties();
        final FineractSecurityProperties security = new FineractSecurityProperties();
        final FineractSecuritySession session = new FineractSecuritySession();
        session.setIdleTimeoutMinutes(10);
        security.setSession(session);
        final FineractSecuritySystemUser systemUser = new FineractSecuritySystemUser();
        systemUser.setDefaultSessionExpirySeconds(86400);
        security.setSystemUser(systemUser);
        fineractProperties.setSecurity(security);

        underTest = new SessionHandlerServiceImpl(userSessionRepository, accessTokenGenerationService, fineractProperties,
                configurationDomainService, platformUserRepository, jdbcTemplate);
        stubSessionLockAcquired();
    }

    @Test
    public void createSessionForHumanUserUsesSlidingIdlePolicy() {
        final AppUser user = humanUser(1L, "human", false);
        when(accessTokenGenerationService.generateRandomToken()).thenReturn("token-1");

        final String token = underTest.createSession(user, null);

        assertEquals("token-1", token);
        verify(jdbcTemplate).queryForObject(eq("SELECT GET_LOCK(?, ?)"), eq(Integer.class), eq("fineract:user_session:1"), eq(2));
        verify(userSessionRepository).deleteAllByUserId(1L);
        final ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(captor.capture());
        assertEquals(SessionPolicy.SLIDING_IDLE, captor.getValue().getSessionPolicyEnum());
        assertEquals(null, captor.getValue().getExpiresAt());
    }

    @Test
    public void createSessionForSystemUserUsesHardExpiryPolicy() {
        final AppUser user = systemUser(2L, "integration", 3600, false);
        when(accessTokenGenerationService.generateRandomToken()).thenReturn("token-2");
        when(userSessionRepository.findValidHardExpirySessions(eq(2L), any(LocalDateTime.class))).thenReturn(List.of());

        underTest.createSession(user, "client-a");

        verify(jdbcTemplate).queryForObject(eq("SELECT GET_LOCK(?, ?)"), eq(Integer.class), eq("fineract:user_session:2"), eq(2));
        verify(userSessionRepository).deleteAllByUserId(2L);
        final ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(captor.capture());
        assertEquals(SessionPolicy.HARD_EXPIRY, captor.getValue().getSessionPolicyEnum());
        assertEquals("client-a", captor.getValue().getSessionLabel());
        final LocalDateTime expiresAt = captor.getValue().getExpiresAt();
        assertNotNull(expiresAt);
        assertTrue(expiresAt.isAfter(now().minusSeconds(2)));
        assertTrue(expiresAt.isBefore(now().plusSeconds(3602)));
    }

    @Test
    public void createSessionForSystemUserReusesValidSession() {
        final AppUser user = systemUser(5L, "integration", 3600, false);
        final UserSession existingSession = new UserSession();
        existingSession.setSessionKey("existing-token");
        when(userSessionRepository.findValidHardExpirySessions(eq(5L), any(LocalDateTime.class))).thenReturn(List.of(existingSession));

        assertEquals("existing-token", underTest.createSession(user, "client-a"));

        verify(userSessionRepository, never()).deleteAllByUserId(5L);
        verify(userSessionRepository, never()).save(any());
        verify(accessTokenGenerationService, never()).generateRandomToken();
    }

    @Test
    public void createSessionForSystemUserCreatesNewWhenNoValidSession() {
        final AppUser user = systemUser(6L, "integration", 3600, false);
        when(accessTokenGenerationService.generateRandomToken()).thenReturn("token-6");
        when(userSessionRepository.findValidHardExpirySessions(eq(6L), any(LocalDateTime.class))).thenReturn(List.of());

        assertEquals("token-6", underTest.createSession(user, null));

        verify(userSessionRepository).deleteAllByUserId(6L);
        verify(userSessionRepository).save(any(UserSession.class));
    }

    @Test
    public void createSessionSkipsEvictionWhenMultipleSessionsAllowed() {
        final AppUser user = systemUser(3L, "multi", 3600, true);
        when(accessTokenGenerationService.generateRandomToken()).thenReturn("token-3");
        when(userSessionRepository.findValidHardExpirySessions(eq(3L), any(LocalDateTime.class))).thenReturn(List.of());

        underTest.createSession(user, null);

        verify(jdbcTemplate, never()).queryForObject(eq("SELECT GET_LOCK(?, ?)"), eq(Integer.class), eq("fineract:user_session:3"), eq(2));
        verify(userSessionRepository, never()).deleteAllByUserId(eq(3L));
    }

    @Test
    public void invalidateUserSessionsUsesSessionMutexAndDeletesSessions() {
        underTest.invalidateUserSessions(4L);

        verify(jdbcTemplate).queryForObject(eq("SELECT GET_LOCK(?, ?)"), eq(Integer.class), eq("fineract:user_session:4"), eq(2));
        verify(userSessionRepository).deleteAllByUserId(4L);
        verify(jdbcTemplate).queryForObject(eq("SELECT RELEASE_LOCK(?)"), eq(Integer.class), eq("fineract:user_session:4"));
    }

    @Test
    public void createSessionFailsFastWhenSessionMutexNotAcquired() {
        final AppUser user = humanUser(1L, "human", false);
        when(jdbcTemplate.queryForObject(eq("SELECT GET_LOCK(?, ?)"), eq(Integer.class), eq("fineract:user_session:1"), eq(2)))
                .thenReturn(0);

        assertThrows(PessimisticLockingFailureException.class, () -> underTest.createSession(user, null));

        verify(userSessionRepository, never()).deleteAllByUserId(1L);
        verify(userSessionRepository, never()).save(any());
    }

    @Test
    public void validateSlidingSessionExtendsLastUsedAt() {
        final LocalDateTime now = now();
        final UserSession session = new UserSession();
        session.setSessionKey("slide-token");
        session.setUserName("human");
        session.setIsValid(true);
        session.setLastUsedAt(now.minusMinutes(1));
        session.setSessionPolicyEnum(SessionPolicy.SLIDING_IDLE);
        when(userSessionRepository.findBySessionKey("slide-token")).thenReturn(Optional.of(session));

        final String username = underTest.validateAndExtractUsername("slide-token");

        assertEquals("human", username);
        verify(userSessionRepository).save(session);
    }

    @Test
    public void validateHardExpirySessionDoesNotExtendExpiry() {
        final LocalDateTime now = now();
        final UserSession session = new UserSession();
        session.setSessionKey("hard-token");
        session.setUserName("system");
        session.setIsValid(true);
        session.setLastUsedAt(now.minusMinutes(1));
        session.setExpiresAt(now.plusHours(1));
        session.setSessionPolicyEnum(SessionPolicy.HARD_EXPIRY);
        when(userSessionRepository.findBySessionKey("hard-token")).thenReturn(Optional.of(session));

        underTest.validateAndExtractUsername("hard-token");

        verify(userSessionRepository, never()).save(any());
    }

    @Test
    public void validateExpiredHardExpirySessionFails() {
        final LocalDateTime now = now();
        final UserSession session = new UserSession();
        session.setSessionKey("expired-token");
        session.setUserName("system");
        session.setIsValid(true);
        session.setExpiresAt(now.minusMinutes(1));
        session.setSessionPolicyEnum(SessionPolicy.HARD_EXPIRY);
        when(userSessionRepository.findBySessionKey("expired-token")).thenReturn(Optional.of(session));

        assertThrows(SessionAuthenticationException.class, () -> underTest.validateAndExtractUsername("expired-token"));
    }

    private void stubSessionLockAcquired() {
        lenient().when(jdbcTemplate.queryForObject(eq("SELECT GET_LOCK(?, ?)"), eq(Integer.class), any(), eq(2))).thenReturn(1);
        lenient().when(jdbcTemplate.queryForObject(eq("SELECT RELEASE_LOCK(?)"), eq(Integer.class), any())).thenReturn(1);
    }

    private LocalDateTime now() {
        return DateUtils.getLocalDateTimeOfTenant();
    }

    private AppUser humanUser(final Long id, final String username, final boolean allowMultipleSessions) {
        final AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(id);
        lenient().when(user.getUsername()).thenReturn(username);
        lenient().when(user.isSystemUser()).thenReturn(false);
        when(user.isAllowMultipleSessions()).thenReturn(allowMultipleSessions);
        return user;
    }

    private AppUser systemUser(final Long id, final String username, final int expirySeconds, final boolean allowMultipleSessions) {
        final AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(id);
        lenient().when(user.getUsername()).thenReturn(username);
        when(user.isSystemUser()).thenReturn(true);
        lenient().when(user.getSessionExpirySeconds()).thenReturn(expirySeconds);
        when(user.isAllowMultipleSessions()).thenReturn(allowMultipleSessions);
        return user;
    }
}
