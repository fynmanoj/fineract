package org.apache.fineract.infrastructure.security.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.domain.PlatformUserRepository;
import org.apache.fineract.infrastructure.security.domain.SessionPolicy;
import org.apache.fineract.infrastructure.security.domain.UserSession;
import org.apache.fineract.infrastructure.security.domain.UserSessionRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.exception.UserNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionHandlerServiceImpl implements SessionHandlerService {

    private final UserSessionRepository userSessionRepository;
    private final AccessTokenGenerationService accessTokenGenerationService;
    private final FineractProperties fineractProperties;
    private final ConfigurationDomainService configurationDomainService;
    private final PlatformUserRepository platformUserRepository;

    @Transactional
    @Override
    public String createSession(final AppUser user, final String sessionLabel) {
        if (!user.isAllowMultipleSessions()) {
            final List<UserSession> existingSessions = userSessionRepository.findAllByUserId(user.getId());
            userSessionRepository.deleteAll(existingSessions);
        }

        final LocalDateTime now = DateUtils.getLocalDateTimeOfTenant();
        final String sessionKey = accessTokenGenerationService.generateRandomToken();
        final UserSession newSession = new UserSession();
        newSession.setSessionKey(sessionKey);
        newSession.setUserId(user.getId());
        newSession.setUserName(user.getUsername());
        newSession.setIsValid(true);
        newSession.setCreatedAt(now);
        newSession.setLastUsedAt(now);
        newSession.setSessionLabel(sessionLabel);

        if (user.isSystemUser()) {
            newSession.setSessionPolicyEnum(SessionPolicy.HARD_EXPIRY);
            final int expirySeconds = resolveSessionExpirySeconds(user);
            newSession.setExpiresAt(now.plusSeconds(expirySeconds));
        } else {
            newSession.setSessionPolicyEnum(SessionPolicy.SLIDING_IDLE);
            newSession.setExpiresAt(null);
        }

        userSessionRepository.save(newSession);
        log.debug("Created {} session for user {} with label {}", newSession.getSessionPolicy(), user.getUsername(), sessionLabel);
        return sessionKey;
    }

    @Transactional
    @Override
    @Deprecated
    public String getCustomAuthenticationKey(final byte[] base64EncodedAuthenticationKey, final Long userId, final String userName) {
        final AppUser user = platformUserRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        return createSession(user, null);
    }

    @Override
    @Transactional
    public String validateAndExtractUsername(final String sessionKey) {
        final UserSession userSession = userSessionRepository.findBySessionKey(sessionKey)
                .orElseThrow(() -> new BadCredentialsException("Session not found"));
        if (!Boolean.TRUE.equals(userSession.getIsValid())) {
            throw new InvalidBearerTokenException("Session is not valid");
        }

        final LocalDateTime now = DateUtils.getLocalDateTimeOfTenant();
        if (SessionPolicy.HARD_EXPIRY == userSession.getSessionPolicyEnum()) {
            if (userSession.getExpiresAt() != null && userSession.getExpiresAt().isBefore(now)) {
                throw new SessionAuthenticationException("Session has expired");
            }
        } else {
            final int idleTimeoutMinutes = resolveIdleTimeoutMinutes();
            if (userSession.getLastUsedAt() != null
                    && userSession.getLastUsedAt().isBefore(now.minusMinutes(idleTimeoutMinutes))) {
                throw new SessionAuthenticationException("Session has expired");
            }
            userSession.setLastUsedAt(now);
            userSessionRepository.save(userSession);
        }

        return userSession.getUserName();
    }

    @Transactional
    @Override
    public void invalidateUserSessions(final Long userId) {
        final List<UserSession> sessions = userSessionRepository.findAllByUserId(userId);
        userSessionRepository.deleteAll(sessions);
    }

    private int resolveIdleTimeoutMinutes() {
        if (fineractProperties.getSecurity() != null && fineractProperties.getSecurity().getSession() != null) {
            return fineractProperties.getSecurity().getSession().getIdleTimeoutMinutes();
        }
        return 10;
    }

    private int resolveSessionExpirySeconds(final AppUser user) {
        if (user.getSessionExpirySeconds() != null && user.getSessionExpirySeconds() > 0) {
            return user.getSessionExpirySeconds();
        }
        if (fineractProperties.getSecurity() != null && fineractProperties.getSecurity().getSystemUser() != null) {
            return fineractProperties.getSecurity().getSystemUser().getDefaultSessionExpirySeconds();
        }
        return configurationDomainService.retrieveDefaultSystemUserSessionExpirySeconds();
    }

}
