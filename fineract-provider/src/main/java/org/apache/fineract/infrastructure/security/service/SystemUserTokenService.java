package org.apache.fineract.infrastructure.security.service;

import org.apache.fineract.infrastructure.hooks.domain.Hook;

public interface SystemUserTokenService {

    String getValidTokenForUser(Long systemUserId);

    String getHookSystemToken(Hook hook);

    void invalidateUserSessions(Long userId);
}
