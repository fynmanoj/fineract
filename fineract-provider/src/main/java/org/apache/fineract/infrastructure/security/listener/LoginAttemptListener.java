/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.security.listener;

import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.infrastructure.security.domain.PlatformUserRepository;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class LoginAttemptListener implements ApplicationListener<org.springframework.context.ApplicationEvent> {

    private final PlatformUserRepository platformUserRepository;

    @PostConstruct
    public void init() {
        log.info("✅ LoginAttemptListener registered in Spring context!");
    }

    @Override
    @Transactional
    public void onApplicationEvent(org.springframework.context.ApplicationEvent event) {
        if (event instanceof AuthenticationFailureBadCredentialsEvent failureEvent) {
            handleFailure(failureEvent);
        } else if (event instanceof AuthenticationSuccessEvent successEvent) {
            handleSuccess(successEvent);
        }
    }

    private void handleFailure(AuthenticationFailureBadCredentialsEvent event) {
        String username = (String) event.getAuthentication().getPrincipal();
        log.warn("🚫 Login failed for user: {}", username);

        Optional<AppUser> userOpt = platformUserRepository.findByUsername(username);

        userOpt.ifPresent(appUser -> {
            appUser.incrementFailedLoginAttempts();

            if (appUser.isAccountLocked()) {
                log.warn("🔒 User '{}' is now LOCKED due to failed login attempts ({}).",
                        username, appUser.getFailedLoginAttempts());
            } else {
                log.info("⚠�? User '{}' failed login attempt #{}", username, appUser.getFailedLoginAttempts());
            }

            platformUserRepository.save(appUser);
        });
    }

    private void handleSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        if (auth.getPrincipal() instanceof AppUser appUser) {
            if (appUser.getFailedLoginAttempts() > 0) {
                appUser.resetFailedLoginAttempts();
                platformUserRepository.save(appUser);
                log.info("✅ Reset failed login attempts for user: {}", appUser.getUsername());
            }
        }
    }
}
