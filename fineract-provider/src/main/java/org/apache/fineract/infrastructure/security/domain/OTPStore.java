/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.fineract.infrastructure.security.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "m_otp_store", indexes = {
        @Index(name = "idx_m_otp_store_username", columnList = "username")
})
public class OTPStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "otp_code", nullable = false, length = 10)
    private String otpCode;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "expiry_time", nullable = false)
    private LocalDateTime expiryTime;

    // 🔹 Default constructor (required by JPA)
    public OTPStore() {}

    // 🔹 Convenience constructor
    public OTPStore(String username, String otpCode, LocalDateTime generatedAt, LocalDateTime expiryTime) {
        this.username = username;
        this.otpCode = otpCode;
        this.generatedAt = generatedAt;
        this.expiryTime = expiryTime;
    }

    // 🔹 Getters and Setters
    public Long getId() { return id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getOtpCode() { return otpCode; }
    public void setOtpCode(String otpCode) { this.otpCode = otpCode; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

    public LocalDateTime getExpiryTime() { return expiryTime; }
    public void setExpiryTime(LocalDateTime expiryTime) { this.expiryTime = expiryTime; }
}
