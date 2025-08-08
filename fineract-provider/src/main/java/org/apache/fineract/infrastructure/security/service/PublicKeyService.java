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

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Service for generating and caching RSA public keys using Redis.
 * Caches the public key for a configurable TTL to avoid regenerating it frequently.
 */
@Service
public class PublicKeyService {

    private static final String PUBLIC_KEY_CACHE_KEY = "rsa:publicKey";
    private static final long TTL_SECONDS = 300; // 5 minutes TTL

    private final StringRedisTemplate redisTemplate;

    @Autowired
    public PublicKeyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Retrieves the cached public key or generates a new one if expired/missing.
     * @return Base64-encoded public key string.
     */
    public String getPublicKey() {
        String cachedKey = redisTemplate.opsForValue().get(PUBLIC_KEY_CACHE_KEY);
        if (cachedKey != null) {
            return cachedKey;
        }

        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair pair = keyGen.generateKeyPair();
            PublicKey publicKey = pair.getPublic();
            String encodedKey = Base64.getEncoder().encodeToString(publicKey.getEncoded());

            redisTemplate.opsForValue().set(PUBLIC_KEY_CACHE_KEY, encodedKey, TTL_SECONDS, TimeUnit.SECONDS);
            return encodedKey;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA public key", e);
        }
    }
}

