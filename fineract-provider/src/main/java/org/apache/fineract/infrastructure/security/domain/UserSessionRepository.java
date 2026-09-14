package org.apache.fineract.infrastructure.security.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, String>, JpaSpecificationExecutor<UserSession> {

    Optional<UserSession> findByUserId(Long userId);

    List<UserSession> findAllByUserId(Long userId);

    Optional<UserSession> findBySessionKey(String sessionKey);

    @Query("SELECT s FROM UserSession s WHERE s.userId = :userId AND s.isValid = true AND s.sessionPolicy = 'HARD_EXPIRY' "
            + "AND s.expiresAt > :now ORDER BY s.expiresAt DESC")
    List<UserSession> findValidHardExpirySessions(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.expiresAt IS NOT NULL AND s.expiresAt < :now")
    int deleteExpiredHardExpirySessions(@Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.sessionPolicy = 'SLIDING_IDLE' AND s.lastUsedAt < :cutoff")
    int deleteIdleSlidingSessions(@Param("cutoff") LocalDateTime cutoff);
}
