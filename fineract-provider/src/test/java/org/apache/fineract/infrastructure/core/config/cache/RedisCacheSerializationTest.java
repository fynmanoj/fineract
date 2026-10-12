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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.codes.data.CodeValueData;
import org.apache.fineract.infrastructure.configuration.domain.GlobalConfigurationProperty;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.hooks.domain.Hook;
import org.apache.fineract.organisation.office.data.OfficeData;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.fund.data.FundData;
import org.apache.fineract.useradministration.data.AppUserData;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

class RedisCacheSerializationTest {

    private final RedisSerializer<Object> serializer = RedisCacheConfig.redisCacheValueSerializer();

    @Test
    void serializesFineractPlatformTenantWithConcreteType() {
        final FineractPlatformTenant tenant = new FineractPlatformTenant(1L, "shirikiana", "Shirikiana", "Africa/Nairobi", null);

        final FineractPlatformTenant deserialized = roundTrip(tenant, FineractPlatformTenant.class);

        assertEquals("shirikiana", deserialized.getTenantIdentifier());
    }

    @Test
    void serializesCollectionOfFinalChargeDataDtos() {
        final Collection<ChargeData> charges = List.of(ChargeData.lookup(1L, "Fee", false));

        final Object deserialized = roundTrip(charges);
        assertInstanceOf(Collection.class, deserialized);
        final Object first = ((Collection<?>) deserialized).iterator().next();
        assertInstanceOf(ChargeData.class, first, "final DTO elements must not deserialize as LinkedHashMap");
    }

    @Test
    void serializesCollectionOfFinalAppUserDataDtos() {
        final Collection<AppUserData> users = List.of(AppUserData.dropdown(1L, "jane"));

        final Object deserialized = roundTrip(users);
        final Object first = ((Collection<?>) deserialized).iterator().next();
        assertInstanceOf(AppUserData.class, first);
    }

    @Test
    void serializesCollectionOfFinalFundDataDtos() {
        final Collection<FundData> funds = List.of(FundData.instance(1L, "General", null));

        final Object deserialized = roundTrip(funds);
        final Object first = ((Collection<?>) deserialized).iterator().next();
        assertInstanceOf(FundData.class, first);
    }

    @Test
    void serializesOfficeDataDto() {
        final OfficeData office = OfficeData.testInstance(1L, "Head Office");

        assertEquals("Head Office", roundTrip(office, OfficeData.class).getName());
    }

    @Test
    void serializesCodeValueDataDto() {
        final CodeValueData codeValue = CodeValueData.instance(1L, "Active", 1, true, false);

        assertEquals("Active", roundTrip(codeValue, CodeValueData.class).getName());
    }

    @Test
    void serializesGlobalConfigurationPropertyEntity() {
        final GlobalConfigurationProperty property = new GlobalConfigurationProperty().setName("test-flag").setEnabled(true);

        assertEquals("test-flag", roundTrip(property, GlobalConfigurationProperty.class).getName());
    }

    @Test
    void serializesListOfFinalHookEntities() {
        final List<Hook> hooks = List.of(new Hook().setName("webhook").setIsActive(true));

        final Object deserialized = roundTrip(hooks);
        final Object first = ((Collection<?>) deserialized).iterator().next();
        assertInstanceOf(Hook.class, first, "final JPA entities in lists must not deserialize as LinkedHashMap");
    }

    @Test
    void serializesTfConfigPrimitivesAndMaps() {
        assertEquals(true, roundTrip(true, Boolean.class));
        assertEquals(42, roundTrip(42, Integer.class));
        assertEquals("otp-text", roundTrip("otp-text", String.class));

        final Map<String, Object> config = new HashMap<>();
        config.put("smsEnabled", true);
        config.put("otpLength", 6);
        final Map<?, ?> deserialized = roundTrip(config, Map.class);
        assertEquals(true, deserialized.get("smsEnabled"));
        assertEquals(6, deserialized.get("otpLength"));
    }

    @Test
    void serializesAppUserDataDto() {
        final AppUserData userData = AppUserData.dropdown(1L, "admin");
        assertNotNull(userData.username());
        assertInstanceOf(AppUserData.class, roundTrip(userData, AppUserData.class));
    }

    private Object roundTrip(final Object value) {
        final byte[] bytes = serializer.serialize(value);
        final Object deserialized = serializer.deserialize(bytes);
        if (deserialized == null) {
            throw new AssertionError("deserialized value was null");
        }
        return deserialized;
    }

    private <T> T roundTrip(final Object value, final Class<T> type) {
        return type.cast(roundTrip(value));
    }
}
