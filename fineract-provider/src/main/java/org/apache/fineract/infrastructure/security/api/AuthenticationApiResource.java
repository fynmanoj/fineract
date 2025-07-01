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
package org.apache.fineract.infrastructure.security.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;
import java.util.*;

import lombok.RequiredArgsConstructor;
import okhttp3.Credentials;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.constants.TwoFactorConstants;
import org.apache.fineract.infrastructure.security.data.AuthenticatedUserData;
import org.apache.fineract.infrastructure.security.service.SessionHandlerService;
import org.apache.fineract.infrastructure.security.service.SpringSecurityPlatformSecurityContext;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.useradministration.data.RoleData;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.Role;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("fineract.security.basicauth.enabled")
@Path("/v1/authentication")
@Tag(name = "Authentication HTTP Basic", description = "An API capability that allows client applications to verify authentication details using HTTP Basic Authentication.")
@RequiredArgsConstructor
public class AuthenticationApiResource {

    @Value("${fineract.security.2fa.enabled}")
    private boolean twoFactorEnabled;

    public static class AuthenticateRequest {

        public String username;
        public String password;
    }

    @Qualifier("customAuthenticationProvider")
    private final DaoAuthenticationProvider customAuthenticationProvider;
    private final ToApiJsonSerializer<AuthenticatedUserData> apiJsonSerializerService;
    private final SpringSecurityPlatformSecurityContext springSecurityPlatformSecurityContext;
    private final ClientReadPlatformService clientReadPlatformService;
    private final SessionHandlerService sessionHandlerService;

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Verify authentication", description = "Authenticates the credentials provided and returns the set roles and permissions allowed.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AuthenticationApiResourceSwagger.PostAuthenticationRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = AuthenticationApiResourceSwagger.PostAuthenticationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Unauthenticated. Please login") })
    public String authenticate(@Parameter(hidden = true) final String apiRequestBodyAsJson,
            @QueryParam("returnClientList") @DefaultValue("false") boolean returnClientList) {
        // TODO FINERACT-819: sort out Jersey so JSON conversion does not have
        // to be done explicitly via GSON here, but implicit by arg
        AuthenticateRequest request = new Gson().fromJson(apiRequestBodyAsJson, AuthenticateRequest.class);
        if (request == null) {
            throw new IllegalArgumentException(
                    "Invalid JSON in BODY (no longer URL param; see FINERACT-726) of POST to /authentication: " + apiRequestBodyAsJson);
        }
        if (request.username == null || request.password == null) {
            throw new IllegalArgumentException("Username or Password is null in JSON (see FINERACT-726) of POST to /authentication: "
                    + apiRequestBodyAsJson + "; username=" + request.username + ", password=" + request.password);
        }


        AppUser appUser = this.springSecurityPlatformSecurityContext.getAppUserByUsername(request.username);

        if (!appUser.isCredentialsNonExpired()) {
            throw new IllegalArgumentException("Account is locked due to multiple failed login attempts.");
        }

        final Authentication authentication = new UsernamePasswordAuthenticationToken(request.username.trim(), request.password.trim());

        Authentication authenticationCheck;
        try {
            authenticationCheck = this.customAuthenticationProvider.authenticate(authentication);
        } catch (Exception e) {

            int failed = appUser.getFailedLoginAttempts() + 1;
            appUser.setFailedLoginAttempts(failed);

            if (failed >= 3) {
                appUser.setCredentialsNonExpired(false);
            }

            this.springSecurityPlatformSecurityContext.saveAppUser(appUser);
            if (e instanceof CredentialsExpiredException) {
                throw validationError("error.msg.password.expired", "Your password has expired. Please change it.");
            } else if (e  instanceof BadCredentialsException) {
                throw validationError("error.msg.invalid.credentials", "Invalid username or password.");
            }else{
                throw new IllegalArgumentException("Invalid username or password.");
            }
        }

        final AppUser principal = (AppUser) authenticationCheck.getPrincipal();
        principal.setFailedLoginAttempts(0);
        principal.setCredentialsNonExpired(true);
        this.springSecurityPlatformSecurityContext.saveAppUser(principal);

        final Collection<String> permissions = new ArrayList<>();
        AuthenticatedUserData authenticatedUserData = new AuthenticatedUserData().setUsername(request.username).setPermissions(permissions);

        if (authenticationCheck.isAuthenticated()) {
            final Collection<GrantedAuthority> authorities = new ArrayList<>(authenticationCheck.getAuthorities());
            for (final GrantedAuthority grantedAuthority : authorities) {
                permissions.add(grantedAuthority.getAuthority());
            }

            final byte[] base64EncodedAuthenticationKey = Base64.getEncoder()
                    .encode((request.username + ":" + request.password).getBytes(StandardCharsets.UTF_8));

            this.springSecurityPlatformSecurityContext.saveAppUser(principal);
            final Collection<RoleData> roles = new ArrayList<>();
            final Set<Role> userRoles = principal.getRoles();
            for (final Role role : userRoles) {
                roles.add(role.toData());
            }

            final Long officeId = principal.getOffice().getId();
            final String officeName = principal.getOffice().getName();

            final Long staffId = principal.getStaffId();
            final String staffDisplayName = principal.getStaffDisplayName();

            final EnumOptionData organisationalRole = principal.organisationalRoleData();

            boolean isTwoFactorRequired = this.twoFactorEnabled
                    && !principal.hasSpecificPermissionTo(TwoFactorConstants.BYPASS_TWO_FACTOR_PERMISSION);
            Long userId = principal.getId();
            if (this.springSecurityPlatformSecurityContext.doesPasswordHasToBeRenewed(principal)) {
                authenticatedUserData = new AuthenticatedUserData().setUsername(request.username).setUserId(userId)
                        .setBase64EncodedAuthenticationKey(sessionHandlerService.getCustomAuthenticationKey(base64EncodedAuthenticationKey, userId, request.username))
                        .setAuthenticated(true).setShouldRenewPassword(true).setTwoFactorAuthenticationRequired(isTwoFactorRequired);
            } else {

                authenticatedUserData = new AuthenticatedUserData().setUsername(request.username).setOfficeId(officeId)
                        .setOfficeName(officeName).setStaffId(staffId).setStaffDisplayName(staffDisplayName)
                        .setOrganisationalRole(organisationalRole).setRoles(roles).setPermissions(permissions).setUserId(principal.getId())
                        .setAuthenticated(true)
                        .setBase64EncodedAuthenticationKey(sessionHandlerService.getCustomAuthenticationKey(base64EncodedAuthenticationKey, userId, request.username))
                        .setTwoFactorAuthenticationRequired(isTwoFactorRequired)
                        .setClients(returnClientList ? clientReadPlatformService.retrieveUserClients(userId) : null);

            }

        }

        return this.apiJsonSerializerService.serialize(authenticatedUserData);

    }
    private PlatformApiDataValidationException validationError(String code, String message) {
        return new PlatformApiDataValidationException(List.of(
                ApiParameterError.parameterError(code, message,null)
        ));
    }
}
