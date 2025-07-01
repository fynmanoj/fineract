package org.apache.fineract.infrastructure.security.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author manoj
 **/


public interface SessionHandlerService {

    @Transactional
    String getCustomAuthenticationKey(final byte[]  base64EncodedAuthenticationKey, Long userId, String userName);

    String validateAndExtractUsername(String sessionKey);

}
