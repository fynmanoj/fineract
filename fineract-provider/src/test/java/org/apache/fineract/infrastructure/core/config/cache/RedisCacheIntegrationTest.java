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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collection;
import java.util.List;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.portfolio.fund.data.FundData;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisCacheIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Test
    void storesAndEvictsValuesInRedisCache() {
        final FineractProperties fineractProperties = RedisCacheTestSupport.buildProperties(REDIS.getHost(), REDIS.getMappedPort(6379));
        final RedisCacheConfig redisCacheConfig = new RedisCacheConfig();
        final RedisConnectionFactory redisConnectionFactory = redisCacheConfig.redisConnectionFactory(fineractProperties);
        final CacheManager cacheManager = redisCacheConfig.redisCacheManager(redisConnectionFactory, fineractProperties);

        final Cache cache = cacheManager.getCache("funds");
        cache.put("defaultfn", "cached-value");

        assertEquals("cached-value", cache.get("defaultfn").get());

        cache.evict("defaultfn");

        assertNull(cache.get("defaultfn"));
    }

    @Test
    void storesTypedCacheValuesUsedByFineractEndpoints() {
        final FineractProperties fineractProperties = RedisCacheTestSupport.buildProperties(REDIS.getHost(), REDIS.getMappedPort(6379));
        final RedisCacheConfig redisCacheConfig = new RedisCacheConfig();
        final RedisConnectionFactory redisConnectionFactory = redisCacheConfig.redisConnectionFactory(fineractProperties);
        final CacheManager cacheManager = redisCacheConfig.redisCacheManager(redisConnectionFactory, fineractProperties);

        final FineractPlatformTenant tenant = new FineractPlatformTenant(1L, "shirikiana", "Shirikiana", "Africa/Nairobi", null);
        cacheManager.getCache("tenantsById").put("shirikiana", tenant);
        assertInstanceOf(FineractPlatformTenant.class, cacheManager.getCache("tenantsById").get("shirikiana").get());

        final Collection<FundData> funds = List.of(FundData.instance(1L, "General", null));
        cacheManager.getCache("funds").put("shirikianafn", funds);
        final Object cachedFunds = cacheManager.getCache("funds").get("shirikianafn").get();
        assertInstanceOf(Collection.class, cachedFunds);
        assertInstanceOf(FundData.class, ((Collection<?>) cachedFunds).iterator().next());
    }
}
