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
package org.apache.fineract.infrastructure.core.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.data.SMTPCredentialsData;
import org.apache.fineract.infrastructure.configuration.service.ExternalServicesPropertiesReadPlatformService;
import org.apache.fineract.infrastructure.core.domain.EmailDetail;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.template.domain.Template;
import org.apache.fineract.template.domain.TemplateRepository;
import org.apache.fineract.template.service.TemplateMergeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class GmailBackedPlatformEmailService implements PlatformEmailService {

    private final ExternalServicesPropertiesReadPlatformService externalServicesReadPlatformService;
    private final TemplateRepository templateRepository;
    private final TemplateMergeService templateMergeService;
    @Autowired
    public GmailBackedPlatformEmailService(final ExternalServicesPropertiesReadPlatformService externalServicesReadPlatformService, TemplateRepository templateRepository, TemplateMergeService templateMergeService) {
        this.externalServicesReadPlatformService = externalServicesReadPlatformService;
        this.templateRepository = templateRepository;
        this.templateMergeService = templateMergeService;
    }


    public void sendToUserAccountWithTemplate(String organisationName, String contactName, String address, String username, String unencodedPassword) {
        String templateNameSub = "EMAIL_NEW_USER_SUBJECT";
        String templateNameBody = "EMAIL_NEW_USER_BODY";
        Map<String, Object> reqMap = new HashMap<>();
        reqMap.put("organisationName", organisationName);
        reqMap.put("contactName", contactName);
        reqMap.put("address", address);
        reqMap.put("username", username);
        reqMap.put("unencodedPassword", unencodedPassword);
        sendEmailWIthTemplates(templateNameSub, templateNameBody, reqMap);
    }
    @Override
    public void sendEmailWIthTemplates(String subjectTemplate, String bodyTemplate, Map<String, Object> reqMap){
        log.info("trying to send email with templates for user {}", reqMap.get("username"));
        String address = (String)reqMap.get("address");
        String contactName = (String)reqMap.get("contactName");
        reqMap.put("tenantName" , ThreadLocalContextUtil.getTenant().getName());
        Template templateSub = this.templateRepository.findByName(subjectTemplate)
                .orElseThrow(()->
                        new GeneralPlatformDomainRuleException("error.msg.templates.not.found", "Template not found", subjectTemplate));
        Template templateBody = this.templateRepository.findByName(bodyTemplate)
                .orElseThrow(()->
                        new GeneralPlatformDomainRuleException("error.msg.templates.not.found", "Template not found", bodyTemplate));

        String emailSubjectText = this.templateMergeService.compile(templateSub, reqMap);
        String emailBodyText = this.templateMergeService.compile(templateBody, reqMap);
        log.debug("templates found , subject {}", emailSubjectText);
        final EmailDetail emailDetail = new EmailDetail(emailSubjectText, emailBodyText, address, contactName);
        sendDefinedEmail(emailDetail);
    }
    @Override
    //@Async
    public void sendToUserAccount(String organisationName, String contactName, String address, String username, String unencodedPassword) {
        try{
            sendToUserAccountWithTemplate(organisationName, contactName, address, username, unencodedPassword);
            return;
        }catch (Exception e){
            log.error("Templates not found");
        }
        final String subject = "Welcome " + contactName + " to " + organisationName;
        final String body = "You are receiving this email as your email account: " + address
                + " has being used to create a user account for an organisation named [" + organisationName + "] on Mifos.\n"
                + "You can login using the following credentials:\nusername: " + username + "\n" + "password: " + unencodedPassword + "\n"
                + "You must change this password upon first log in using Uppercase, Lowercase, number and character.\n"
                + "Thank you and welcome to the organisation.";

        final EmailDetail emailDetail = new EmailDetail(subject, body, address, contactName);
        sendDefinedEmail(emailDetail);

    }

    @Override
    public void sendDefinedEmail(EmailDetail emailDetails) {
        final SMTPCredentialsData smtpCredentialsData = this.externalServicesReadPlatformService.getSMTPCredentials();

        final String authuser = smtpCredentialsData.getUsername();
        final String authpwd = smtpCredentialsData.getPassword();

        final JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(smtpCredentialsData.getHost()); // smtp.gmail.com
        mailSender.setPort(Integer.parseInt(smtpCredentialsData.getPort())); // 587

        // Important: Enable less secure app access for the gmail account used in the following authentication

        mailSender.setUsername(authuser); // use valid gmail address
        mailSender.setPassword(authpwd); // use password of the above gmail account

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.debug", "true");

        // these are the added lines
        props.put("mail.smtp.starttls.enable", "true");
        // props.put("mail.smtp.ssl.enable", "true");

        props.put("mail.smtp.socketFactory.port", Integer.parseInt(smtpCredentialsData.getPort()));
        props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");// NOSONAR
        props.put("mail.smtp.socketFactory.fallback", "true");
        props.put("mail.smtp.connectiontimeout", 10000);
        props.put("mail.smtp.timeout", 10000);
        props.put("mail.smtp.writetimeout", 10000);
        try {
            log.info("sending email start, username: {} timeout {}", authuser, props.get("mail.smtp.connectiontimeout"));
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(smtpCredentialsData.getFromEmail()); // same email address used for the authentication
            message.setTo(emailDetails.getAddress());
            message.setSubject(emailDetails.getSubject());
            message.setText(emailDetails.getBody());
            mailSender.send(message);
            log.info("--------------email sent!-------------");

        } catch (Exception e) {
            log.error("Email sending failed",e);
            throw new PlatformEmailSendException(e);
        }
    }
}
