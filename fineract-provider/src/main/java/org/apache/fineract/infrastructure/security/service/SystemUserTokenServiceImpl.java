package org.apache.fineract.infrastructure.security.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.hooks.domain.Hook;
import org.apache.fineract.infrastructure.security.domain.UserSession;
import org.apache.fineract.infrastructure.security.domain.UserSessionRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.infrastructure.security.domain.PlatformUserRepository;
import org.apache.fineract.useradministration.exception.UserNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemUserTokenServiceImpl implements SystemUserTokenService {

    private static final String HOOK_SESSION_LABEL = "hook-internal";

    private final PlatformUserRepository platformUserRepository;
    private final UserSessionRepository userSessionRepository;
    private final SessionHandlerService sessionHandlerService;
    private final ConfigurationDomainService configurationDomainService;
    private final FineractProperties fineractProperties;

    @Transactional
    @Override
    public String getValidTokenForUser(final Long systemUserId) {
        final AppUser systemUser = platformUserRepository.findById(systemUserId)
                .orElseThrow(() -> new UserNotFoundException(systemUserId));
        if (!systemUser.isSystemUser()) {
            throw new IllegalArgumentException("User is not configured as a system user: " + systemUser.getUsername());
        }

        final List<UserSession> validSessions = userSessionRepository.findValidHardExpirySessions(systemUserId,
                DateUtils.getLocalDateTimeOfTenant());
        if (!validSessions.isEmpty()) {
            return validSessions.get(0).getSessionKey();
        }

        return sessionHandlerService.createSession(systemUser, HOOK_SESSION_LABEL);
    }

    @Transactional
    @Override
    public String getHookSystemToken(final Hook hook) {
        final Long systemUserId = resolveHookSystemUserId(hook);
        return getValidTokenForUser(systemUserId);
    }

    @Transactional
    @Override
    public void invalidateUserSessions(final Long userId) {
        sessionHandlerService.invalidateUserSessions(userId);
    }

    private Long resolveHookSystemUserId(final Hook hook) {
        if (hook != null && hook.getSystemUserId() != null) {
            return hook.getSystemUserId();
        }
        final String username = resolveDefaultHookUsername();
        return platformUserRepository.findByUsername(username).map(AppUser::getId)
                .orElseThrow(() -> new UserNotFoundException(username));
    }

    private String resolveDefaultHookUsername() {
        if (fineractProperties.getSecurity() != null && fineractProperties.getSecurity().getSystemUser() != null
                && fineractProperties.getSecurity().getSystemUser().getDefaultHookUsername() != null) {
            return fineractProperties.getSecurity().getSystemUser().getDefaultHookUsername();
        }
        return configurationDomainService.retrieveDefaultHookSystemUserUsername();
    }
}
