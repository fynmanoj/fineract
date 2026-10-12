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
import java.util.Map;

public final class CacheRegionDefinitions {

    public static final String CONFIG_BY_NAME_CACHE_NAME = "configByName";

    private static final Duration NO_EXPIRY = Duration.ZERO;

    private static final Map<String, Duration> CACHE_REGIONS = Map.ofEntries(Map.entry("users", NO_EXPIRY),
            Map.entry("usersByUsername", NO_EXPIRY), Map.entry("tenantsById", NO_EXPIRY), Map.entry("offices", NO_EXPIRY),
            Map.entry("officesForDropdown", NO_EXPIRY), Map.entry("officesById", NO_EXPIRY), Map.entry("charges", NO_EXPIRY),
            Map.entry("funds", NO_EXPIRY), Map.entry("code_values", NO_EXPIRY), Map.entry("codes", NO_EXPIRY),
            Map.entry("hooks", NO_EXPIRY), Map.entry("tfConfig", NO_EXPIRY), Map.entry(CONFIG_BY_NAME_CACHE_NAME, NO_EXPIRY),
            Map.entry("tellers", NO_EXPIRY), Map.entry("payment_types", NO_EXPIRY), Map.entry("paymentTypesWithCode", NO_EXPIRY),
            Map.entry("userTFAccessToken", Duration.ofHours(2)), Map.entry("useSessionToken", Duration.ofMinutes(10)));

    private CacheRegionDefinitions() {}

    public static Map<String, Duration> getCacheRegions() {
        return CACHE_REGIONS;
    }

    public static boolean hasExpiry(final String cacheName) {
        return CACHE_REGIONS.containsKey(cacheName) && !NO_EXPIRY.equals(CACHE_REGIONS.get(cacheName));
    }

    public static Duration getExpiry(final String cacheName) {
        return CACHE_REGIONS.getOrDefault(cacheName, NO_EXPIRY);
    }
}
