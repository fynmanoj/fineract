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
package org.apache.fineract.infrastructure.bulkimport.populator.shareaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.bulkimport.constants.TemplatePopulateImportConstants;
import org.apache.fineract.infrastructure.bulkimport.populator.ClientSheetPopulator;
import org.apache.fineract.infrastructure.bulkimport.populator.SavingsAccountSheetPopulator;
import org.apache.fineract.infrastructure.bulkimport.populator.SharedProductsSheetPopulator;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;

class SharedAccountWorkBookPopulatorTest {

    @Test
    void populateCreatesClientsNamedRangeWithArithmeticEndRow() {
        ClientSheetPopulator clientSheetPopulator = mock(ClientSheetPopulator.class);
        SharedProductsSheetPopulator sharedProductsSheetPopulator = mock(SharedProductsSheetPopulator.class);
        SavingsAccountSheetPopulator savingsAccountSheetPopulator = mock(SavingsAccountSheetPopulator.class);

        List<ClientData> clients = Collections.nCopies(8117, mock(ClientData.class));
        when(clientSheetPopulator.getClients()).thenReturn(clients);
        when(sharedProductsSheetPopulator.getSharedProductDataList()).thenReturn(Collections.emptyList());

        doAnswer(invocation -> {
            Workbook workbook = invocation.getArgument(0);
            workbook.createSheet(TemplatePopulateImportConstants.CLIENT_SHEET_NAME);
            return null;
        }).when(clientSheetPopulator).populate(any(Workbook.class), anyString());
        doAnswer(invocation -> {
            Workbook workbook = invocation.getArgument(0);
            workbook.createSheet(TemplatePopulateImportConstants.SHARED_PRODUCTS_SHEET_NAME);
            return null;
        }).when(sharedProductsSheetPopulator).populate(any(Workbook.class), anyString());
        doAnswer(invocation -> {
            Workbook workbook = invocation.getArgument(0);
            workbook.createSheet(TemplatePopulateImportConstants.SAVINGS_ACCOUNTS_SHEET_NAME);
            return null;
        }).when(savingsAccountSheetPopulator).populate(any(Workbook.class), anyString());

        SharedAccountWorkBookPopulator populator = new SharedAccountWorkBookPopulator(sharedProductsSheetPopulator, clientSheetPopulator,
                savingsAccountSheetPopulator);

        Workbook workbook = new HSSFWorkbook();
        populator.populate(workbook, "dd MMMM yyyy");

        Name clientsName = workbook.getName("Clients");
        assertThat(clientsName).isNotNull();
        assertThat(clientsName.getRefersToFormula())
                .isEqualTo(TemplatePopulateImportConstants.CLIENT_SHEET_NAME + "!$B$2:$B$8118");
    }
}
