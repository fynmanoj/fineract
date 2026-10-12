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

package org.apache.fineract.infrastructure.cache.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Map;
import org.apache.fineract.infrastructure.cache.CacheApiConstants;
import org.apache.fineract.infrastructure.cache.data.CacheData;
import org.apache.fineract.infrastructure.cache.domain.CacheType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class RuntimeDelegatingCacheManagerTest {

    private ConcurrentMapCacheManager ehCacheManager;
    private ConcurrentMapCacheManager defaultCacheManager;
    private ConcurrentMapCacheManager redisCacheManager;
    private RuntimeDelegatingCacheManager delegatingCacheManager;

    @BeforeEach
    void setUp() throws Exception {
        ehCacheManager = new ConcurrentMapCacheManager("users");
        defaultCacheManager = new ConcurrentMapCacheManager();
        redisCacheManager = new ConcurrentMapCacheManager("users");
        delegatingCacheManager = new RuntimeDelegatingCacheManager(ehCacheManager, defaultCacheManager);
        delegatingCacheManager.setRedisCacheManager(redisCacheManager);
        delegatingCacheManager.afterPropertiesSet();
    }

    @Test
    void switchToMultiNodeActivatesRedisCacheManager() {
        final Map<String, Object> changes = delegatingCacheManager.switchToCache(CacheType.NO_CACHE, CacheType.MULTI_NODE);

        assertEquals(CacheType.MULTI_NODE.getValue(), changes.get(CacheApiConstants.CACHE_TYPE_PARAMETER));
        assertNotNull(delegatingCacheManager.getCache("users"));
        assertTrue(delegatingCacheManager.retrieveAll().stream().filter(CacheData::isEnabled).count() == 1);
    }

    @Test
    void switchToSingleNodeActivatesEhCacheManager() {
        final Map<String, Object> changes = delegatingCacheManager.switchToCache(CacheType.NO_CACHE, CacheType.SINGLE_NODE);

        assertEquals(CacheType.SINGLE_NODE.getValue(), changes.get(CacheApiConstants.CACHE_TYPE_PARAMETER));
        assertNotNull(delegatingCacheManager.getCache("users"));
    }

    @Test
    void switchToMultiNodeWithoutRedisConfiguredThrows() throws Exception {
        final RuntimeDelegatingCacheManager withoutRedis = new RuntimeDelegatingCacheManager(ehCacheManager, defaultCacheManager);
        withoutRedis.afterPropertiesSet();

        assertThrows(IllegalStateException.class,
                () -> withoutRedis.switchToCache(CacheType.NO_CACHE, CacheType.MULTI_NODE));
    }

    @Test
    void switchingToSameCacheTypeProducesNoChanges() {
        delegatingCacheManager.switchToCache(CacheType.NO_CACHE, CacheType.MULTI_NODE);

        final Map<String, Object> changes = delegatingCacheManager.switchToCache(CacheType.MULTI_NODE, CacheType.MULTI_NODE);

        assertTrue(changes.isEmpty());
    }

    @Test
    void retrieveAllReturnsThreeCacheOptions() {
        delegatingCacheManager.switchToCache(CacheType.NO_CACHE, CacheType.MULTI_NODE);

        final Collection<CacheData> caches = delegatingCacheManager.retrieveAll();

        assertEquals(3, caches.size());
        assertEquals(1, caches.stream().filter(CacheData::isEnabled).count());
    }

    @Test
    void switchToNoCacheUsesDefaultCacheManager() {
        delegatingCacheManager.switchToCache(CacheType.NO_CACHE, CacheType.MULTI_NODE);
        delegatingCacheManager.switchToCache(CacheType.MULTI_NODE, CacheType.NO_CACHE);

        final Collection<CacheData> caches = delegatingCacheManager.retrieveAll();
        assertEquals(1, caches.stream().filter(CacheData::isEnabled).count());
        assertTrue(caches.stream().filter(cache -> cache.isEnabled())
                .allMatch(cache -> CacheType.NO_CACHE.getCode().equals(cache.getCacheType().getCode())));
    }
}
