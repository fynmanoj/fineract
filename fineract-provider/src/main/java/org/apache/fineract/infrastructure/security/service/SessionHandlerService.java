package org.apache.fineract.infrastructure.security.service;

import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.transaction.annotation.Transactional;

public interface SessionHandlerService {

    @Transactional
    String createSession(AppUser user, String sessionLabel);

    @Deprecated
    @Transactional
    String getCustomAuthenticationKey(final byte[] base64EncodedAuthenticationKey, Long userId, String userName);

    String validateAndExtractUsername(String sessionKey);

    @Transactional
    void invalidateUserSessions(Long userId);
}
