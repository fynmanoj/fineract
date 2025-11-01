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


package org.apache.fineract.infrastructure.security.service;

import jakarta.transaction.Transactional;
import org.apache.fineract.infrastructure.security.domain.OTPStore;
import org.apache.fineract.infrastructure.security.domain.OTPStoreRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

@Service
public class OTPService {

    private final OTPStoreRepository otpStoreRepository;
    private final Random random = new Random();

    public OTPService(OTPStoreRepository otpStoreRepository) {
        this.otpStoreRepository = otpStoreRepository;
    }

    /**
     * Generates a 6-digit OTP, stores it in the DB with expiry time, and returns the OTP.
     * @param username the user for which OTP is generated
     * @return the generated OTP
     */
    @Transactional
    public String generateOTP(String username) {

        // cleanup existing OTPs for the same user
        otpStoreRepository.deleteByUsername(username);

        // Generate 6-digit OTP
        String otp = String.format("%06d", random.nextInt(900000) + 100000);

        // Set generated and expiry times
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiryTime = now.plusMinutes(10); // OTP valid for 10 minutes

        // Save to DB
        OTPStore otpStore = new OTPStore(username, otp, now, expiryTime);
        otpStoreRepository.save(otpStore);

        // Cleanup expired OTPs
        deleteExpiredOTPs();

        return otp;
    }

    /**
     * Verifies the OTP for a given username.
     * @param username the user
     * @param otp the OTP to verify
     * @return true if valid, false otherwise
     */
    @Transactional
    public boolean verifyOTP(String username, String otp) {
        Optional<OTPStore> optionalOTP = otpStoreRepository.findByUsername(username);

        if (optionalOTP.isEmpty()) return false;

        OTPStore otpStore = optionalOTP.get();

        // Check if expired
        if (otpStore.getExpiryTime().isBefore(LocalDateTime.now())) {
            otpStoreRepository.delete(otpStore);
            return false;
        }

        boolean valid = otpStore.getOtpCode().equals(otp);

        // Delete OTP after verification regardless of result
        otpStoreRepository.delete(otpStore);

        return valid;
    }

    /**
     * Deletes all expired OTPs from the database.
     */
    @Transactional
    public void deleteExpiredOTPs() {
        otpStoreRepository.deleteAllByExpiryTimeBefore(LocalDateTime.now());
    }

    /**
     * Optional helper to fetch the latest OTP (useful for testing)
     */
    public Optional<OTPStore> getLatestOTP(String username) {
        return otpStoreRepository.findByUsername(username);
    }
}
