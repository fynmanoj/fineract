package org.apache.fineract.infrastructure.security.service;

import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.domain.UserSession;
import org.apache.fineract.infrastructure.security.domain.UserSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @Transactional
    public String getBase64EncodedAuthenticationKey(byte[] base64EncodedAuthenticationKey, Long userId) {
        Optional<UserSession> oldSession = this.userSessionRepository.findByUserId(userId);
        if(oldSession.isPresent()){
            this.userSessionRepository.delete(oldSession.get());
        }
        String sessionKey = accessTokenGenerationService.generateRandomToken();
        UserSession newsession = new UserSession(sessionKey, userId,true, DateUtils.getLocalDateTimeOfTenant(), DateUtils.getLocalDateTimeOfTenant());
        userSessionRepository.save(newsession);
        return sessionKey;
        //return new String(base64EncodedAuthenticationKey, StandardCharsets.UTF_8);

    }
    @Override
    public boolean isSavedSession(String sessionKey) {
        Optional<UserSession> userSession = this.userSessionRepository.findBySessionKey(sessionKey);
        return userSession.isPresent();
    }

    /*@Override
    public UserDetails getUserFromSession(String sessionKey) {
        UserSession userSession = this.userSessionRepository.findBySessionKey(sessionKey).orElseThrow(() -> new IllegalArgumentException("Session not found"));

        return userDetailsService.loadUserByUserId(userSession.getUserId());
    }*/
}
