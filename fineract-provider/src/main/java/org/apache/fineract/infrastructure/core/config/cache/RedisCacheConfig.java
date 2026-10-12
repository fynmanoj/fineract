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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "fineract.cache.redis.enabled", havingValue = "true")
public class RedisCacheConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(final FineractProperties fineractProperties) {
        final FineractProperties.FineractCacheRedisProperties redisProperties = getRedisProperties(fineractProperties);
        if (StringUtils.isBlank(redisProperties.getHost())) {
            throw new IllegalStateException("fineract.cache.redis.host must be configured when Redis cache is enabled");
        }

        final RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration(redisProperties.getHost(),
                redisProperties.getPort() != null ? redisProperties.getPort() : 6379);
        if (StringUtils.isNotBlank(redisProperties.getPassword())) {
            redisConfig.setPassword(redisProperties.getPassword());
        }

        final LettuceClientConfiguration.LettuceClientConfigurationBuilder clientConfigBuilder = LettuceClientConfiguration.builder();
        if (redisProperties.getTimeout() != null) {
            clientConfigBuilder.commandTimeout(redisProperties.getTimeout());
        }
        if (redisProperties.isSslEnabled()) {
            clientConfigBuilder.useSsl();
        }

        final LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfig, clientConfigBuilder.build());
        connectionFactory.afterPropertiesSet();
        log.info("Redis connection factory initialized for {}:{} (ssl={})", redisProperties.getHost(), redisConfig.getPort(),
                redisProperties.isSslEnabled());
        return connectionFactory;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(final RedisConnectionFactory redisConnectionFactory) {
        final RedisSerializer<Object> serializer = redisCacheValueSerializer();
        final RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(serializer);
        redisTemplate.setHashValueSerializer(serializer);
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    @Bean("redisCacheManager")
    public CacheManager redisCacheManager(final RedisConnectionFactory redisConnectionFactory, final FineractProperties fineractProperties) {
        final FineractProperties.FineractCacheRedisProperties redisProperties = getRedisProperties(fineractProperties);
        final String keyPrefix = StringUtils.defaultIfBlank(redisProperties.getKeyPrefix(), "fineract");
        final RedisSerializer<Object> serializer = redisCacheValueSerializer();

        final RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(keyPrefix + ":").serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                .disableCachingNullValues();

        final Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        for (Map.Entry<String, Duration> region : CacheRegionDefinitions.getCacheRegions().entrySet()) {
            final Duration expiry = region.getValue();
            if (expiry != null && !expiry.isZero()) {
                cacheConfigurations.put(region.getKey(), defaultConfig.entryTtl(expiry));
            }
        }

        return RedisCacheManager.builder(redisConnectionFactory).cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations).build();
    }

    /**
     * JDK serialization mirrors in-process Ehcache behaviour: cached values are arbitrary Java graphs
     * (collections, final DTOs, JPA entities) that are not reliably round-tripped by Jackson JSON.
     */
    static RedisSerializer<Object> redisCacheValueSerializer() {
        return new JdkSerializationRedisSerializer();
    }

    private static FineractProperties.FineractCacheRedisProperties getRedisProperties(final FineractProperties fineractProperties) {
        if (fineractProperties.getCache() == null || fineractProperties.getCache().getRedis() == null) {
            throw new IllegalStateException("fineract.cache.redis properties must be configured when Redis cache is enabled");
        }
        return fineractProperties.getCache().getRedis();
    }
}
