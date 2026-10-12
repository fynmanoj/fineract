package org.apache.fineract.infrastructure.security.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionHandlerServiceImpl implements SessionHandlerService {

    static final String SESSION_LOCK_PREFIX = "fineract:user_session:";
    static final int SESSION_LOCK_TIMEOUT_SECONDS = 2;

    private final UserSessionRepository userSessionRepository;
    private final AccessTokenGenerationService accessTokenGenerationService;
    private final FineractProperties fineractProperties;
    private final ConfigurationDomainService configurationDomainService;
    private final PlatformUserRepository platformUserRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    @Override
    public String createSession(final AppUser user, final String sessionLabel) {
        if (!user.isAllowMultipleSessions()) {
            return withUserSessionMutex(user.getId(), () -> createSessionUnlocked(user, sessionLabel));
        }
        return createSessionUnlocked(user, sessionLabel);
    }

    private String createSessionUnlocked(final AppUser user, final String sessionLabel) {
        if (user.isSystemUser()) {
            final List<UserSession> validSessions = userSessionRepository.findValidHardExpirySessions(user.getId(),
                    DateUtils.getLocalDateTimeOfTenant());
            if (!validSessions.isEmpty()) {
                return validSessions.get(0).getSessionKey();
            }
            if (!user.isAllowMultipleSessions()) {
                userSessionRepository.deleteAllByUserId(user.getId());
            }
        } else if (!user.isAllowMultipleSessions()) {
            userSessionRepository.deleteAllByUserId(user.getId());
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
        withUserSessionMutex(userId, () -> userSessionRepository.deleteAllByUserId(userId));
    }

    private <T> T withUserSessionMutex(final Long userId, final Supplier<T> action) {
        final String lockName = SESSION_LOCK_PREFIX + userId;
        final Integer acquired = this.jdbcTemplate.queryForObject("SELECT GET_LOCK(?, ?)", Integer.class, lockName,
                SESSION_LOCK_TIMEOUT_SECONDS);
        if (acquired == null || acquired != 1) {
            throw new PessimisticLockingFailureException("Could not acquire session lock for user " + userId);
        }
        try {
            return action.get();
        } finally {
            this.jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
        }
    }

    private void withUserSessionMutex(final Long userId, final Runnable action) {
        withUserSessionMutex(userId, () -> {
            action.run();
            return null;
        });
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
