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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.cache.CacheApiConstants;
import org.apache.fineract.infrastructure.cache.CacheEnumerations;
import org.apache.fineract.infrastructure.cache.data.CacheData;
import org.apache.fineract.infrastructure.cache.domain.CacheType;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.stereotype.Component;

/**
 * At present this implementation of {@link CacheManager} just delegates to the real {@link CacheManager} to use.
 *
 * By default it is {@link NoOpCacheManager} but we can change that by checking some persisted configuration in the
 * database on startup and allow user to switch implementation through UI/API
 */
@Component(value = "runtimeDelegatingCacheManager")
@Slf4j
public class RuntimeDelegatingCacheManager implements CacheManager, InitializingBean {

    private final CacheManager ehCacheManager;
    private final CacheManager defaultCacheManager;
    private CacheManager redisCacheManager;
    private CacheManager currentCacheManager;

    public RuntimeDelegatingCacheManager(@Qualifier("ehCacheManager") final CacheManager ehCacheManager,
            @Qualifier("defaultCacheManager") final CacheManager defaultCacheManager) {
        this.ehCacheManager = ehCacheManager;
        this.defaultCacheManager = defaultCacheManager;
    }

    @Autowired(required = false)
    @Qualifier("redisCacheManager")
    public void setRedisCacheManager(final CacheManager redisCacheManager) {
        this.redisCacheManager = redisCacheManager;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        currentCacheManager = defaultCacheManager;
    }

    @Override
    public Cache getCache(final String name) {
        return currentCacheManager.getCache(name);
    }

    @Override
    public Collection<String> getCacheNames() {
        return currentCacheManager.getCacheNames();
    }

    public Collection<CacheData> retrieveAll() {

        final boolean noCacheEnabled = currentCacheManager == defaultCacheManager;
        final boolean ehCacheEnabled = currentCacheManager == ehCacheManager;
        final boolean redisCacheEnabled = redisCacheManager != null && currentCacheManager == redisCacheManager;

        final EnumOptionData noCacheType = CacheEnumerations.cacheType(CacheType.NO_CACHE);
        final EnumOptionData singleNodeCacheType = CacheEnumerations.cacheType(CacheType.SINGLE_NODE);
        final EnumOptionData multiNodeCacheType = CacheEnumerations.cacheType(CacheType.MULTI_NODE);

        final CacheData noCache = CacheData.instance(noCacheType, noCacheEnabled);
        final CacheData singleNodeCache = CacheData.instance(singleNodeCacheType, ehCacheEnabled);
        final CacheData multiNodeCache = CacheData.instance(multiNodeCacheType, redisCacheEnabled);

        return Arrays.asList(noCache, singleNodeCache, multiNodeCache);
    }

    public Map<String, Object> switchToCache(final CacheType fromCacheType, final CacheType toCacheType) {

        final Map<String, Object> changes = new HashMap<>();

        if (fromCacheType != toCacheType) {
            changes.put(CacheApiConstants.CACHE_TYPE_PARAMETER, toCacheType.getValue());
        }

        switch (toCacheType) {
            case INVALID -> log.warn("Invalid cache type used");
            case NO_CACHE -> {
                currentCacheManager = defaultCacheManager;
            }
            case SINGLE_NODE -> {
                if (fromCacheType != toCacheType) {
                    clearCacheManager(ehCacheManager);
                }
                currentCacheManager = ehCacheManager;
                validateCacheManager(currentCacheManager);
            }
            case MULTI_NODE -> {
                if (redisCacheManager == null) {
                    throw new IllegalStateException(
                            "Redis cache is not configured. Enable fineract.cache.redis.enabled and provide connection details.");
                }
                if (fromCacheType != toCacheType) {
                    clearCacheManager(redisCacheManager);
                }
                currentCacheManager = redisCacheManager;
                validateCacheManager(currentCacheManager);
            }
        }

        return changes;
    }

    private void validateCacheManager(final CacheManager cacheManager) {
        if (cacheManager.getCacheNames().isEmpty()) {
            log.error("No caches configured for activated CacheManager {}", cacheManager);
        }
    }

    @SuppressFBWarnings(value = "DCN_NULLPOINTER_EXCEPTION", justification = "TODO: fix this!")
    private void clearCacheManager(final CacheManager cacheManager) {
        if (cacheManager == null) {
            return;
        }
        Iterable<String> cacheNames = cacheManager.getCacheNames();
        for (String cacheName : cacheNames) {
            try {
                if (Objects.nonNull(cacheManager.getCache(cacheName))) {
                    Objects.requireNonNull(cacheManager.getCache(cacheName)).clear();
                }
            } catch (NullPointerException npe) {
                log.warn(npe.getMessage());
            }
        }
    }
}
