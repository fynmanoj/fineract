package org.apache.fineract.infrastructure.security.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
import org.apache.fineract.infrastructure.core.config.FineractProperties.FineractSecuritySystemUser;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.hooks.domain.Hook;
import org.apache.fineract.infrastructure.security.domain.PlatformUserRepository;
import org.apache.fineract.infrastructure.security.domain.UserSession;
import org.apache.fineract.infrastructure.security.domain.UserSessionRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SystemUserTokenServiceImplTest {

    @Mock
    private PlatformUserRepository platformUserRepository;
    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private SessionHandlerService sessionHandlerService;
    @Mock
    private ConfigurationDomainService configurationDomainService;

    private FineractProperties fineractProperties;
    private SystemUserTokenServiceImpl underTest;

    @BeforeEach
    public void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.now(ZoneId.systemDefault()))));

        fineractProperties = new FineractProperties();
        final FineractSecurityProperties security = new FineractSecurityProperties();
        final FineractSecuritySystemUser systemUser = new FineractSecuritySystemUser();
        systemUser.setDefaultHookUsername("template_system");
        security.setSystemUser(systemUser);
        fineractProperties.setSecurity(security);

        underTest = new SystemUserTokenServiceImpl(platformUserRepository, userSessionRepository, sessionHandlerService,
                configurationDomainService, fineractProperties);
    }

    @Test
    public void getValidTokenForUserReusesExistingSession() {
        final AppUser systemUser = buildSystemUser(10L, "template_system");
        final UserSession existingSession = new UserSession();
        existingSession.setSessionKey("existing-token");
        when(platformUserRepository.findById(10L)).thenReturn(Optional.of(systemUser));
        when(userSessionRepository.findValidHardExpirySessions(eq(10L), any(LocalDateTime.class))).thenReturn(List.of(existingSession));

        assertEquals("existing-token", underTest.getValidTokenForUser(10L));
    }

    @Test
    public void getValidTokenForUserCreatesSessionWhenNoneExists() {
        final AppUser systemUser = buildSystemUser(10L, "template_system");
        when(platformUserRepository.findById(10L)).thenReturn(Optional.of(systemUser));
        when(userSessionRepository.findValidHardExpirySessions(eq(10L), any(LocalDateTime.class))).thenReturn(List.of());
        when(sessionHandlerService.createSession(systemUser, "hook-internal")).thenReturn("new-token");

        assertEquals("new-token", underTest.getValidTokenForUser(10L));
    }

    @Test
    public void getValidTokenForUserRejectsNonSystemUser() {
        final AppUser humanUser = mock(AppUser.class);
        when(humanUser.isSystemUser()).thenReturn(false);
        when(platformUserRepository.findById(5L)).thenReturn(Optional.of(humanUser));

        assertThrows(IllegalArgumentException.class, () -> underTest.getValidTokenForUser(5L));
    }

    @Test
    public void getHookSystemTokenUsesHookOverride() {
        final Hook hook = new Hook();
        hook.setSystemUserId(99L);
        final AppUser hookUser = buildSystemUser(99L, "hook-user");
        when(platformUserRepository.findById(99L)).thenReturn(Optional.of(hookUser)); // called twice: resolve + getValidToken
        when(userSessionRepository.findValidHardExpirySessions(eq(99L), any(LocalDateTime.class))).thenReturn(List.of());
        when(sessionHandlerService.createSession(hookUser, "hook-internal")).thenReturn("hook-token");

        assertEquals("hook-token", underTest.getHookSystemToken(hook));
    }

    @Test
    public void getHookSystemTokenUsesDefaultUsernameWhenHookOverrideMissing() {
        final AppUser defaultUser = buildSystemUser(11L, "template_system");
        when(platformUserRepository.findByUsername("template_system")).thenReturn(Optional.of(defaultUser));
        when(platformUserRepository.findById(11L)).thenReturn(Optional.of(defaultUser));
        when(userSessionRepository.findValidHardExpirySessions(eq(11L), any(LocalDateTime.class))).thenReturn(List.of());
        when(sessionHandlerService.createSession(defaultUser, "hook-internal")).thenReturn("default-token");

        assertEquals("default-token", underTest.getHookSystemToken(null));
    }

    @Test
    public void invalidateUserSessionsDelegatesToSessionHandler() {
        underTest.invalidateUserSessions(7L);
        verify(sessionHandlerService).invalidateUserSessions(7L);
    }

    private AppUser buildSystemUser(final Long id, final String username) {
        final AppUser user = mock(AppUser.class);
        lenient().when(user.getId()).thenReturn(id);
        lenient().when(user.getUsername()).thenReturn(username);
        when(user.isSystemUser()).thenReturn(true);
        return user;
    }
}
