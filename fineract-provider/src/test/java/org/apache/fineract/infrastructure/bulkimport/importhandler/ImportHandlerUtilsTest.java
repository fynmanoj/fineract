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
package org.apache.fineract.infrastructure.bulkimport.importhandler;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionEnumData;
import org.junit.jupiter.api.Test;

class ImportHandlerUtilsTest {

    private static final String DATE_FORMAT = "dd MMMM yyyy";

    @Test
    void toSavingsTransactionCommandJsonIncludesOnlyApiFields() {
        SavingsAccountTransactionEnumData transactionType = new SavingsAccountTransactionEnumData(null, null, "Deposit");
        SavingsAccountTransactionData transaction = SavingsAccountTransactionData.importInstance(BigDecimal.TEN,
                LocalDate.of(2026, 10, 2), 1L, "12312", "123123", "12", "123", "221221323", 230L, transactionType, 1, "en", DATE_FORMAT);

        String json = ImportHandlerUtils.toSavingsTransactionCommandJson(transaction, DATE_FORMAT);
        JsonObject payload = JsonParser.parseString(json).getAsJsonObject();

        assertThat(payload.keySet()).containsExactlyInAnyOrder("transactionDate", "transactionAmount", "paymentTypeId", "locale",
                "dateFormat", "accountNumber", "checkNumber", "routingCode", "receiptNumber", "bankNumber");
        assertThat(payload.get("transactionDate").getAsString()).isEqualTo("02 October 2026");
        assertThat(payload.get("transactionAmount").getAsString()).isEqualTo("10");
        assertThat(payload.get("paymentTypeId").getAsLong()).isEqualTo(1L);
        assertThat(payload.has("accountId")).isFalse();
        assertThat(payload.has("date")).isFalse();
        assertThat(payload.has("amount")).isFalse();
        assertThat(payload.has("submittedOnDate")).isFalse();
        assertThat(payload.has("isManualTransaction")).isFalse();
        assertThat(payload.has("lienTransaction")).isFalse();
        assertThat(payload.has("chargesPaidByData")).isFalse();
        assertThat(payload.has("accountNo")).isFalse();
    }

    @Test
    void toSavingsTransactionCommandJsonOmitsBlankOptionalFields() {
        SavingsAccountTransactionEnumData transactionType = new SavingsAccountTransactionEnumData(null, null, "Deposit");
        SavingsAccountTransactionData transaction = SavingsAccountTransactionData.importInstance(BigDecimal.TEN,
                LocalDate.of(2026, 10, 2), 1L, null, null, null, null, null, 230L, transactionType, 1, "en", DATE_FORMAT);

        String json = ImportHandlerUtils.toSavingsTransactionCommandJson(transaction, DATE_FORMAT);
        JsonObject payload = JsonParser.parseString(json).getAsJsonObject();

        assertThat(payload.keySet()).containsExactlyInAnyOrder("transactionDate", "transactionAmount", "paymentTypeId", "locale",
                "dateFormat");
    }
}
