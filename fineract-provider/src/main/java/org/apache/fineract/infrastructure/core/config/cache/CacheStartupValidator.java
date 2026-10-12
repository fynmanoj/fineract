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

package org.apache.fineract.infrastructure.core.config.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.cache.domain.CacheType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheStartupValidator implements ApplicationListener<ApplicationReadyEvent> {

    private final ConfigurationDomainService configurationDomainService;
    private final FineractProperties fineractProperties;

    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        final CacheType activeCacheType = this.configurationDomainService.getActiveCacheType();
        if (activeCacheType != CacheType.MULTI_NODE) {
            if (this.redisConnectionFactory != null) {
                log.info("Redis is configured but cache type is {} ({}). Set c_cache.cache_type_enum=3 or switch via PUT /v1/caches to activate MULTI_NODE Redis cache.",
                        activeCacheType, activeCacheType.getCode());
            }
            return;
        }

        final FineractProperties.FineractCacheRedisProperties redisProperties = this.fineractProperties.getCache() == null ? null
                : this.fineractProperties.getCache().getRedis();
        if (redisProperties == null || !redisProperties.isConfigured()) {
            throw new IllegalStateException(
                    "MULTI_NODE cache is active but Redis is not configured. Set fineract.cache.redis.enabled=true and fineract.cache.redis.host.");
        }

        if (this.redisConnectionFactory == null) {
            throw new IllegalStateException("MULTI_NODE cache is active but Redis connection factory is not available.");
        }

        try (RedisConnection connection = this.redisConnectionFactory.getConnection()) {
            final String pingResponse = connection.ping();
            if (pingResponse == null || !"PONG".equalsIgnoreCase(pingResponse)) {
                throw new IllegalStateException("Redis ping failed for MULTI_NODE cache. Response: " + pingResponse);
            }
            log.info("Redis connectivity verified for MULTI_NODE cache at {}:{}", redisProperties.getHost(), redisProperties.getPort());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to connect to Redis for MULTI_NODE cache: " + exception.getMessage(), exception);
        }
    }
}
