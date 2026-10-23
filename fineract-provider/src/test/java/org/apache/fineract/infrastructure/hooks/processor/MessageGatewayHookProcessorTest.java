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
package org.apache.fineract.infrastructure.hooks.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.hooks.api.HookApiConstants;
import org.apache.fineract.infrastructure.hooks.domain.Hook;
import org.apache.fineract.infrastructure.hooks.domain.HookConfiguration;
import org.apache.fineract.infrastructure.sms.domain.SmsMessage;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository;
import org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService;
import org.apache.fineract.organisation.staff.domain.StaffRepositoryWrapper;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.guarantor.domain.Guarantor;
import org.apache.fineract.portfolio.loanaccount.guarantor.domain.GuarantorRepository;
import org.apache.fineract.template.domain.Template;
import org.apache.fineract.template.domain.TemplateRepository;
import org.apache.fineract.template.service.TemplateMergeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MessageGatewayHookProcessorTest {

    private static final Long LOAN_APPLICANT_ID = 10L;
    private static final Long GUARANTOR_ID = 55L;
    private static final Long SHARE_CLIENT_ID = 8161L;

    @Mock
    private ClientRepositoryWrapper clientRepository;
    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private TemplateMergeService templateMergeService;
    @Mock
    private SmsMessageRepository smsMessageRepository;
    @Mock
    private SmsMessageScheduledJobService smsMessageScheduledJobService;
    @Mock
    private GuarantorRepository guarantorRepository;
    @Mock
    private StaffRepositoryWrapper staffRepositoryWrapper;

    private MessageGatewayHookProcessor processor;

    @BeforeEach
    public void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.now(ZoneId.systemDefault()))));
        processor = new MessageGatewayHookProcessor(clientRepository, templateRepository, templateMergeService, smsMessageRepository,
                smsMessageScheduledJobService, guarantorRepository, staffRepositoryWrapper);
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    public void createGuarantorSmsGoesToClientGuarantorInsteadOfLoanApplicant() throws Exception {
        Client guarantorClient = org.mockito.Mockito.mock(Client.class);
        when(guarantorClient.getId()).thenReturn(77L);
        when(guarantorClient.mobileNo()).thenReturn("9991112222");
        when(guarantorClient.getDisplayName()).thenReturn("Jane Guarantor");
        when(clientRepository.findOneWithNotFoundDetection(77L)).thenReturn(guarantorClient);

        Guarantor guarantor = org.mockito.Mockito.mock(Guarantor.class);
        when(guarantor.isExistingCustomer()).thenReturn(true);
        when(guarantor.getEntityId()).thenReturn(77L);
        when(guarantorRepository.findById(GUARANTOR_ID)).thenReturn(Optional.of(guarantor));

        Hook hook = hook();
        Template template = template("GUARANTOR_CREATE");
        when(templateMergeService.compile(eq(template), any(), eq(hook))).thenReturn("You are a guarantor");

        processor.process(hook, guarantorPayload(), "GUARANTOR", "CREATE", null);

        SmsMessage smsMessage = savedSms();
        assertEquals("9991112222", smsMessage.getMobileNo());
        assertSame(guarantorClient, smsMessage.getClient());
        assertNull(smsMessage.getStaff());
        assertEquals("Jane Guarantor", capturedTemplateScope(hook).get("clientName"));
        assertEquals(77L, capturedTemplateScope(hook).get("clientId"));
        verify(clientRepository, never()).findOneWithNotFoundDetection(LOAN_APPLICANT_ID);
    }

    @Test
    public void createGuarantorSmsGoesToExternalGuarantorMobile() throws Exception {
        Guarantor guarantor = org.mockito.Mockito.mock(Guarantor.class);
        when(guarantor.isExistingCustomer()).thenReturn(false);
        when(guarantor.isExistingEmployee()).thenReturn(false);
        when(guarantor.isExternalGuarantor()).thenReturn(true);
        when(guarantor.getMobileNumber()).thenReturn("8883334444");
        when(guarantor.getDisplayName()).thenReturn("Alex External");
        when(guarantorRepository.findById(GUARANTOR_ID)).thenReturn(Optional.of(guarantor));

        Hook hook = hook();
        Template template = template("GUARANTOR_CREATE");
        when(templateMergeService.compile(eq(template), any(), eq(hook))).thenReturn("You are a guarantor");

        processor.process(hook, guarantorPayload(), "GUARANTOR", "CREATE", null);

        SmsMessage smsMessage = savedSms();
        assertEquals("8883334444", smsMessage.getMobileNo());
        assertNull(smsMessage.getClient());
        assertNull(smsMessage.getStaff());
        assertEquals("Alex External", capturedTemplateScope(hook).get("clientName"));
        verify(clientRepository, never()).findOneWithNotFoundDetection(any());
        verify(staffRepositoryWrapper, never()).findOneWithNotFoundDetection(any());
    }

    @Test
    public void createShareAccountSmsUsesRootClientId() throws Exception {
        Client shareClient = org.mockito.Mockito.mock(Client.class);
        when(shareClient.mobileNo()).thenReturn("7770001111");
        when(shareClient.getDisplayName()).thenReturn("Share Holder");
        when(clientRepository.findOneWithNotFoundDetection(SHARE_CLIENT_ID)).thenReturn(shareClient);

        Hook hook = hook();
        Template template = template("SHAREACCOUNT_CREATE");
        when(templateMergeService.compile(eq(template), any(), eq(hook))).thenReturn("Share account created");

        processor.process(hook, shareAccountPayloadWithRootClientId(), "SHAREACCOUNT", "CREATE", null);

        SmsMessage smsMessage = savedSms();
        assertEquals("7770001111", smsMessage.getMobileNo());
        assertSame(shareClient, smsMessage.getClient());
        assertEquals("Share Holder", capturedTemplateScope(hook).get("clientName"));
    }

    @Test
    public void createShareAccountSmsIgnoresClientIdOnlyUnderRequest() throws Exception {
        Hook hook = hook();
        template("SHAREACCOUNT_CREATE");

        processor.process(hook, shareAccountPayloadWithRequestClientIdOnly(), "SHAREACCOUNT", "CREATE", null);

        verify(smsMessageRepository, never()).save(any());
        verifyNoInteractions(smsMessageScheduledJobService);
        verify(clientRepository, never()).findOneWithNotFoundDetection(any());
    }

    private Hook hook() {
        Hook hook = new Hook();
        hook.setConfig(Set.of(HookConfiguration.createNew(hook, "string", HookApiConstants.SMSProviderIdParamName, "7")));
        return hook;
    }

    private Template template(final String name) {
        Template template = org.mockito.Mockito.mock(Template.class);
        when(templateRepository.findByName(name)).thenReturn(Optional.of(template));
        return template;
    }

    private String guarantorPayload() {
        return "{\"clientId\":" + LOAN_APPLICANT_ID + ",\"loanId\":20,\"response\":{\"resourceId\":" + GUARANTOR_ID + "}}";
    }

    private String shareAccountPayloadWithRootClientId() {
        return "{\"clientId\":" + SHARE_CLIENT_ID + ",\"request\":{\"clientId\":\"" + SHARE_CLIENT_ID + "\"}}";
    }

    private String shareAccountPayloadWithRequestClientIdOnly() {
        return "{\"request\":{\"clientId\":\"" + SHARE_CLIENT_ID + "\"}}";
    }

    private SmsMessage savedSms() {
        ArgumentCaptor<SmsMessage> smsCaptor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(smsMessageRepository).save(smsCaptor.capture());
        return smsCaptor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedTemplateScope(final Hook hook) throws Exception {
        ArgumentCaptor<Map<String, Object>> scopeCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateMergeService).compile(any(), scopeCaptor.capture(), eq(hook));
        return scopeCaptor.getValue();
    }
}
