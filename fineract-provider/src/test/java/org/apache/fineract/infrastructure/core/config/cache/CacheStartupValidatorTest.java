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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.cache.domain.CacheType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@ExtendWith(MockitoExtension.class)
class CacheStartupValidatorTest {

    @Mock
    private ConfigurationDomainService configurationDomainService;

    @Mock
    private ApplicationReadyEvent applicationReadyEvent;

    @InjectMocks
    private CacheStartupValidator cacheStartupValidator;

    private FineractProperties fineractProperties;

    @BeforeEach
    void setUp() {
        fineractProperties = new FineractProperties();
        final FineractProperties.FineractCacheProperties cacheProperties = new FineractProperties.FineractCacheProperties();
        final FineractProperties.FineractCacheRedisProperties redisProperties = new FineractProperties.FineractCacheRedisProperties();
        redisProperties.setEnabled(true);
        redisProperties.setHost("localhost");
        redisProperties.setPort(6379);
        cacheProperties.setRedis(redisProperties);
        fineractProperties.setCache(cacheProperties);
        cacheStartupValidator = new CacheStartupValidator(configurationDomainService, fineractProperties);
    }

    @Test
    void skipsValidationWhenNoCacheIsActive() {
        when(configurationDomainService.getActiveCacheType()).thenReturn(CacheType.NO_CACHE);

        assertDoesNotThrow(() -> cacheStartupValidator.onApplicationEvent(applicationReadyEvent));
    }

    @Test
    void failsWhenMultiNodeActiveButRedisIsNotConfigured() {
        when(configurationDomainService.getActiveCacheType()).thenReturn(CacheType.MULTI_NODE);
        fineractProperties.getCache().getRedis().setEnabled(false);

        assertThrows(IllegalStateException.class, () -> cacheStartupValidator.onApplicationEvent(applicationReadyEvent));
    }

    @Test
    void failsWhenMultiNodeActiveButRedisConnectionFactoryMissing() {
        when(configurationDomainService.getActiveCacheType()).thenReturn(CacheType.MULTI_NODE);

        assertThrows(IllegalStateException.class, () -> cacheStartupValidator.onApplicationEvent(applicationReadyEvent));
    }

    @Test
    void failsWhenMultiNodeActiveButRedisPingFails() {
        when(configurationDomainService.getActiveCacheType()).thenReturn(CacheType.MULTI_NODE);
        final RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
        final RedisConnection redisConnection = mock(RedisConnection.class);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("ERROR");
        cacheStartupValidator = new CacheStartupValidator(configurationDomainService, fineractProperties);
        injectRedisConnectionFactory(cacheStartupValidator, redisConnectionFactory);

        assertThrows(IllegalStateException.class, () -> cacheStartupValidator.onApplicationEvent(applicationReadyEvent));
    }

    @Test
    void succeedsWhenMultiNodeActiveAndRedisResponds() {
        when(configurationDomainService.getActiveCacheType()).thenReturn(CacheType.MULTI_NODE);
        final RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
        final RedisConnection redisConnection = mock(RedisConnection.class);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");
        cacheStartupValidator = new CacheStartupValidator(configurationDomainService, fineractProperties);
        injectRedisConnectionFactory(cacheStartupValidator, redisConnectionFactory);

        assertDoesNotThrow(() -> cacheStartupValidator.onApplicationEvent(applicationReadyEvent));
    }

    private static void injectRedisConnectionFactory(final CacheStartupValidator validator,
            final RedisConnectionFactory redisConnectionFactory) {
        try {
            final var field = CacheStartupValidator.class.getDeclaredField("redisConnectionFactory");
            field.setAccessible(true);
            field.set(validator, redisConnectionFactory);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
