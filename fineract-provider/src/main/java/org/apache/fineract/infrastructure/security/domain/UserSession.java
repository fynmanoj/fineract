package org.apache.fineract.infrastructure.security.domain;

/**
 * @author manoj
 **/

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "m_user_sessions")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class UserSession {

    @Id
    @Column(name = "session_key")
    private String sessionKey;
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_name")
    private String  userName;

    @Column(name = "is_valid")
    private Boolean isValid;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

}
