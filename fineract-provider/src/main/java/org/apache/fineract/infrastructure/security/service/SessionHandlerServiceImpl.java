package org.apache.fineract.infrastructure.security.service;

import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.domain.UserSession;
import org.apache.fineract.infrastructure.security.domain.UserSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * @author manoj
 **/

@Service
public class SessionHandlerServiceImpl implements SessionHandlerService{
    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private AccessTokenGenerationService accessTokenGenerationService;

    @Autowired
    private TenantAwareJpaPlatformUserDetailsService userDetailsService;

    @Transactional
    @Override
    public String getCustomAuthenticationKey(final byte[]  base64EncodedAuthenticationKey, Long userId, String userName) {
        Optional<UserSession> oldSession = this.userSessionRepository.findByUserId(userId);
        if(oldSession.isPresent()){
            this.userSessionRepository.delete(oldSession.get());
        }
        String sessionKey = accessTokenGenerationService.generateRandomToken();
        //String sessionKey = new String(base64EncodedAuthenticationKey, StandardCharsets.UTF_8);
        UserSession newsession = new UserSession(sessionKey, userId, userName, true, DateUtils.getLocalDateTimeOfTenant(), DateUtils.getLocalDateTimeOfTenant());
        userSessionRepository.save(newsession);
        return sessionKey;

    }
    @Override
    @Transactional
    public String validateAndExtractUsername(String sessionKey) {
        UserSession userSession = this.userSessionRepository.findBySessionKey(sessionKey)
                .orElseThrow(() -> new BadCredentialsException("Session not found"));
        if(!userSession.getIsValid()) {
            throw new InvalidBearerTokenException("Session is not valid");
        }
        if (userSession.getLastUsedAt() != null && userSession.getLastUsedAt().isBefore(DateUtils.getLocalDateTimeOfTenant().minusMinutes(10))) {
            throw new SessionAuthenticationException("Session has expired");

        }
        userSession.setLastUsedAt(DateUtils.getLocalDateTimeOfTenant());
        userSessionRepository.save(userSession);
        return userSession.getUserName();
    }

}
