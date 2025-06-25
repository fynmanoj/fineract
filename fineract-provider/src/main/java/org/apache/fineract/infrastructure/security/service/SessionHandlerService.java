package org.apache.fineract.infrastructure.security.service;

import org.springframework.security.core.userdetails.UserDetails;

/**
 * @author manoj
 **/


public interface SessionHandlerService {
    String getBase64EncodedAuthenticationKey(byte[] base64EncodedAuthenticationKey, Long userId);

    boolean isSavedSession(String sessionKey);

    //UserDetails getUserFromSession(String sessionKey);
}
