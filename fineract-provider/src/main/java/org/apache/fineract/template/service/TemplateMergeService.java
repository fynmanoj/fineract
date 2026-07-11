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
package org.apache.fineract.template.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Base64;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.template.domain.Template;
import org.apache.fineract.template.domain.TemplateFunctions;
import org.apache.fineract.template.exception.TemplateForbiddenException;
import org.apache.fineract.infrastructure.core.config.FineractProperties.FineractTemplateProperties.InternalUser;

@Slf4j
@RequiredArgsConstructor
public class TemplateMergeService {

    private final FineractProperties fineractProperties;

    // TODO Replace this with appropriate alternative available in Guava
    private static String getStringFromInputStream(final InputStream is) {
        final StringBuilder sb = new StringBuilder();

        String line;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        } catch (final IOException e) {
            log.error("getStringFromInputStream() failed", e);
        }

        return sb.toString();
    }

    public String compile(final Template template, final Map<String, Object> scopes) {
        scopes.put("static", new TemplateFunctions());

        final MustacheFactory mf = new DefaultMustacheFactory();
        final Mustache mustache = mf.compile(new StringReader(template.getText()), template.getName());

        getCompiledMapFromMappers(template.getMappersAsMap(), scopes);

        expandMapArrays(scopes);

        final StringWriter stringWriter = new StringWriter();
        mustache.execute(stringWriter, scopes);

        return stringWriter.toString();
    }

    private Map<String, Object> getCompiledMapFromMappers(final Map<String, String> data, final Map<String, Object> scopes) {
        final MustacheFactory mf = new DefaultMustacheFactory();

        if (data != null) {
            for (final Map.Entry<String, String> entry : data.entrySet()) {
                final Mustache mappersMustache = mf.compile(new StringReader(entry.getValue()), "");
                final StringWriter stringWriter = new StringWriter();

                mappersMustache.execute(stringWriter, scopes);
                String url = stringWriter.toString();
                if (scopes.get("BASE_URI") == null) {
                    scopes.put("BASE_URI", fineractProperties.getBaseUrl());
                }
                if (!url.startsWith("http")) {
                    log.info("Base URL : {}", scopes.get("BASE_URI"));
                    String baseUrl = scopes.get("BASE_URI").toString();

                    if (baseUrl.endsWith("/") && url.startsWith("/")) {
                        url = baseUrl.substring(0, baseUrl.length() - 1) + url;
                    } else {
                        url = baseUrl + url;
                    }
                    log.info("Calling URL: {}", url);
                }
                try {
                    scopes.put(entry.getKey(), getMapFromUrl(url));
                } catch (final IOException e) {
                    log.error("getCompiledMapFromMappers() failed", e);
                }
            }
        }
        return scopes;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMapFromUrl(final String url) throws IOException {

        final HttpURLConnection connection = getConnection(url);

        try {

            final String response = getStringFromInputStream(connection.getInputStream());

            HashMap<String, Object> result = new HashMap<>();

            if ("text/plain".equals(connection.getContentType())) {
                result.put("src", response);
            } else {
                result = new ObjectMapper().readValue(response, HashMap.class);
            }

            return result;

        } catch (IOException e) {

            log.error("HTTP Status : {}", connection.getResponseCode());
            log.error("URL         : {}", url);

            if (connection.getErrorStream() != null) {
                log.error("Error Body : {}", getStringFromInputStream(connection.getErrorStream()));
            }

            throw e;
        }
    }

    private HttpURLConnection getConnection(final String url) {
        if (fineractProperties.getTemplate() != null && fineractProperties.getTemplate().isRegexWhitelistEnabled()) {
            boolean whitelisted = false;

            if (fineractProperties.getTemplate().getRegexWhitelist() != null
                    && !fineractProperties.getTemplate().getRegexWhitelist().isEmpty()) {
                for (String urlPattern : fineractProperties.getTemplate().getRegexWhitelist()) {
                    Pattern pattern = Pattern.compile(urlPattern);
                    Matcher matcher = pattern.matcher(url);
                    if (matcher.matches()) {
                        whitelisted = true;
                        break;
                    }
                }
            }

            if (!whitelisted) {
                throw new TemplateForbiddenException(url);
            }
        }

        final InternalUser internalUser = fineractProperties
                .getTemplate()
                .getInternalUser();

        final String name = internalUser.getUsername();
        final String password = internalUser.getPassword();

        log.info("TemplateMergeService using internal user: {}", name);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            String credentials = name + ":" + password;

            String basicAuth = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

            if (ThreadLocalContextUtil.getTenant() == null) {
                throw new IllegalStateException("Tenant context is missing");
            }

            String tenantId = ThreadLocalContextUtil.getTenant().getTenantIdentifier();

            log.info("Using tenant: {}", tenantId);

            connection.setRequestProperty(
                    "Authorization",
                    "Basic " + basicAuth);

            connection.setRequestProperty(
                    "Fineract-Platform-TenantId",
                    tenantId);

            connection.setRequestProperty(
                    "Accept",
                    "application/json");

            connection.setRequestProperty(
                    "Content-Type",
                    "application/json");

            log.info("Authorization Header: {}", connection.getRequestProperty("Authorization"));
            log.info("Tenant Header: {}", connection.getRequestProperty("Fineract-Platform-TenantId"));

            connection.setRequestMethod("GET");
            log.info("Request Method: {}", connection.getRequestMethod());
            log.info("Tenant Header: {}", tenantId);
            log.info("Authorization header configured.");
            log.info("Connection created for URL: {}", url);
            log.info("Connection created for URL: {}", url);
            TrustModifier.relaxHostChecking(connection);
            connection.setDoInput(true);

        } catch (IOException | KeyManagementException | NoSuchAlgorithmException | KeyStoreException e) {
            log.error("getConnection() failed, return null", e);
        }

        return connection;
    }

    @SuppressWarnings("unchecked")
    private void expandMapArrays(Object value) {
        if (value instanceof Map) {
            Map<String, Object> valueAsMap = (Map<String, Object>) value;
            // Map<String, Object> newValue = null;
            Map<String, Object> valueAsMapTemp = new HashMap<>();
            for (Map.Entry<String, Object> valueAsMapEntry : valueAsMap.entrySet()) {
                Object valueAsMapEntryValue = valueAsMapEntry.getValue();
                if (valueAsMapEntryValue instanceof Map) { // JSON Object
                    expandMapArrays(valueAsMapEntryValue);
                } else if (valueAsMapEntryValue instanceof Iterable) { // JSON
                    // Array
                    Iterable<Object> valueAsMapEntryValueIterable = (Iterable<Object>) valueAsMapEntryValue;
                    String valueAsMapEntryKey = valueAsMapEntry.getKey();
                    int i = 0;
                    for (Object object : valueAsMapEntryValueIterable) {
                        valueAsMapTemp.put(valueAsMapEntryKey + "#" + i, object);
                        ++i;
                        expandMapArrays(object);

                    }
                }

            }
            valueAsMap.putAll(valueAsMapTemp);

        }
    }

}
