package org.apache.fineract.infrastructure.security.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * @author manoj
 **/


public interface UserSessionRepository extends JpaRepository<UserSession, Long>, JpaSpecificationExecutor<UserSession> {

    Optional<UserSession> findByUserId(Long userId);

    Optional<UserSession> findBySessionKey(String sessionKey);
}
