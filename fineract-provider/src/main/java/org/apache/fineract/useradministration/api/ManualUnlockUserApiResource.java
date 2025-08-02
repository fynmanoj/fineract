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
package org.apache.fineract.useradministration.api;

import org.apache.fineract.infrastructure.security.service.SpringSecurityPlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.api.UnlockUserRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/unlock-user")
public class ManualUnlockUserApiResource {

    private final SpringSecurityPlatformSecurityContext securityContext;

    public ManualUnlockUserApiResource(SpringSecurityPlatformSecurityContext securityContext) {
        this.securityContext = securityContext;
        System.out.println("✅ ManualUnlockUserApiResource bean initialized.");
    }

    @PostMapping
    public Map<String, String> unlockUser(@RequestBody UnlockUserRequest request) {
        System.out.println("📬 unlockUser() called");

        if (request == null) {
            System.out.println("❌ Request body is null.");
            throw new IllegalArgumentException("Request body cannot be null.");
        }

        String username = request.getUsername();
        System.out.println("👤 Username received: " + username);

        if (username == null || username.isBlank()) {
            System.out.println("❌ Username is missing or blank.");
            throw new IllegalArgumentException("Username must be provided.");
        }

        AppUser user = this.securityContext.getAppUserByUsername(username);
        if (user == null) {
            System.out.println("❌ User not found in the system.");
            throw new IllegalArgumentException("User not found.");
        }

        System.out.println("🔓 Unlocking user: " + username);
        user.setAccountNonLocked(true);
        user.setFailedLoginAttempts(0);
        user.setCredentialsLockedAt(null);
        this.securityContext.saveAppUser(user);

        System.out.println("✅ User successfully unlocked and saved.");
        return Map.of("message", "User '" + username + "' has been unlocked");
    }
}
