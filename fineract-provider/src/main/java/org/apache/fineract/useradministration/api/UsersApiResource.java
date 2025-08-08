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
package org.apache.fineract.useradministration.api;

import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.InputStream;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.bulkimport.data.GlobalEntityType;
import org.apache.fineract.infrastructure.bulkimport.service.BulkImportWorkbookPopulatorService;
import org.apache.fineract.infrastructure.bulkimport.service.BulkImportWorkbookService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.UploadRequest;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.api.AuthenticationApiResource;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.utils.PasswordValidator;
import org.apache.fineract.organisation.office.data.OfficeData;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.useradministration.data.AppUserData;
import org.apache.fineract.useradministration.data.ChangePasswordRequest;
import org.apache.fineract.useradministration.data.FirstTimePasswordChangeRequest;
import org.apache.fineract.useradministration.data.OtpEntry;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.service.AppUserReadPlatformService;
import org.apache.fineract.useradministration.service.AppUserWritePlatformService;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Path("/v1/users")
@Component
@Tag(name = "Users", description = "An API capability to support administration of application users.")
@RequiredArgsConstructor
public class UsersApiResource {

    /**
     * The set of parameters that are supported in response for {@link AppUserData}.
     */
    private static final Set<String> RESPONSE_DATA_PARAMETERS = new HashSet<>(Arrays.asList("id", "officeId", "officeName", "username",
            "firstname", "lastname", "email", "allowedOffices", "availableRoles", "selectedRoles", "staff"));

    private static final String RESOURCE_NAME_FOR_PERMISSIONS = "USER";

    private final PlatformSecurityContext context;
    private final AppUserReadPlatformService readPlatformService;
    private final OfficeReadPlatformService officeReadPlatformService;
    private final DefaultToApiJsonSerializer<AppUserData> toApiJsonSerializer;
    private final ApiRequestParameterHelper apiRequestParameterHelper;
    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final BulkImportWorkbookPopulatorService bulkImportWorkbookPopulatorService;
    private final BulkImportWorkbookService bulkImportWorkbookService;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final AppUserRepository appUserRepository;
    //for testing purposes only
    // Temporary in-memory OTP cache (for testing only)
    private final Map<String, OtpEntry> otpCache = new ConcurrentHashMap<>();



    @GET
    @Operation(summary = "Retrieve list of users", description = "Example Requests:\n" + "\n" + "users\n" + "\n" + "\n"
            + "users?fields=id,username,email,officeName")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = UsersApiResourceSwagger.GetUsersResponse.class)))) })
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveAll(@Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);

        final Collection<AppUserData> users = this.readPlatformService.retrieveAllUsers();

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, users, RESPONSE_DATA_PARAMETERS);
    }

    @GET
    @Path("{userId}")
    @Operation(summary = "Retrieve a User", description = "Example Requests:\n" + "\n" + "users/1\n" + "\n" + "\n"
            + "users/1?template=true\n" + "\n" + "\n" + "users/1?fields=username,officeName")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = UsersApiResourceSwagger.GetUsersUserIdResponse.class))) })
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String retrieveOne(@PathParam("userId") @Parameter(description = "userId") final Long userId, @Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS, userId);

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());

        AppUserData user = this.readPlatformService.retrieveUser(userId);
        if (settings.isTemplate()) {
            final Collection<OfficeData> offices = this.officeReadPlatformService.retrieveAllOfficesForDropdown();
            user = AppUserData.template(user, offices);
        }

        return this.toApiJsonSerializer.serialize(settings, user, RESPONSE_DATA_PARAMETERS);
    }

    @GET
    @Path("template")
    @Operation(summary = "Retrieve User Details Template", description = "This is a convenience resource. It can be useful when building maintenance user interface screens for client applications. The template data returned consists of any or all of:\n"
            + "\n" + "Field Defaults\n" + "Allowed description Lists\n" + "Example Request:\n" + "\n" + "users/template")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = UsersApiResourceSwagger.GetUsersTemplateResponse.class))) })
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String template(@Context final UriInfo uriInfo) {

        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);

        final AppUserData user = this.readPlatformService.retrieveNewUserDetails();

        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, user, RESPONSE_DATA_PARAMETERS);
    }

    @POST
    @Operation(summary = "Create a User", description = "Adds new application user.\n" + "\n"
            + "Note: Password information is not required (or processed). Password details at present are auto-generated and then sent to the email account given (which is why it can take a few seconds to complete).\n"
            + "\n" + "Mandatory Fields: \n" + "username, firstname, lastname, email, officeId, roles, sendPasswordToEmail\n" + "\n"
            + "Optional Fields: \n" + "staffId,passwordNeverExpires,isSelfServiceUser,clients")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = UsersApiResourceSwagger.PostUsersRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = UsersApiResourceSwagger.PostUsersResponse.class))) })
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String create(@Parameter(hidden = true) final String apiRequestBodyAsJson) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder() //
                .createUser() //
                .withJson(apiRequestBodyAsJson) //
                .build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    @PUT
    @Path("{userId}")
    @Operation(summary = "Update a User", description = "When updating a password you must provide the repeatPassword parameter also.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = UsersApiResourceSwagger.PutUsersUserIdRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = UsersApiResourceSwagger.PutUsersUserIdResponse.class))) })
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String update(@PathParam("userId") @Parameter(description = "userId") final Long userId,
            @Parameter(hidden = true) final String apiRequestBodyAsJson) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder() //
                .updateUser(userId) //
                .withJson(apiRequestBodyAsJson) //
                .build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    @DELETE
    @Path("{userId}")
    @Operation(summary = "Delete a User", description = "Removes the user and the associated roles and permissions.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = UsersApiResourceSwagger.DeleteUsersUserIdResponse.class))) })
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String delete(@PathParam("userId") @Parameter(description = "userId") final Long userId) {

        final CommandWrapper commandRequest = new CommandWrapperBuilder() //
                .deleteUser(userId) //
                .build();

        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        return this.toApiJsonSerializer.serialize(result);
    }

    @GET
    @Path("downloadtemplate")
    @Produces("application/vnd.ms-excel")
    public Response getUserTemplate(@QueryParam("officeId") final Long officeId, @QueryParam("staffId") final Long staffId,
            @QueryParam("dateFormat") final String dateFormat) {
        return bulkImportWorkbookPopulatorService.getTemplate(GlobalEntityType.USERS.toString(), officeId, staffId, dateFormat);
    }

    @POST
    @Path("uploadtemplate")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RequestBody(description = "Upload users template", content = {
            @Content(mediaType = MediaType.MULTIPART_FORM_DATA, schema = @Schema(implementation = UploadRequest.class)) })
    public String postUsersTemplate(@FormDataParam("file") InputStream uploadedInputStream,
            @FormDataParam("file") FormDataContentDisposition fileDetail, @FormDataParam("locale") final String locale,
            @FormDataParam("dateFormat") final String dateFormat) {
        final Long importDocumentId = this.bulkImportWorkbookService.importWorkbook(GlobalEntityType.USERS.toString(), uploadedInputStream,
                fileDetail, locale, dateFormat);
        return this.toApiJsonSerializer.serialize(importDocumentId);
    }

    @Autowired
    private AppUserWritePlatformService appUserWritePlatformService;
    @POST
    @Path("change-password")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String changePassword(@Parameter(hidden = true) final ChangePasswordRequest apiRequestBodyAsJson) {
        //authenticationApiResource.authenticate(apiRequestBodyAsJson, false);
        /*final CommandWrapper commandRequest = new CommandWrapperBuilder()
                .changePasswordCommand()
                .withJson(apiRequestBodyAsJson)
                .build();*/

        final CommandProcessingResult result = this.appUserWritePlatformService.changeOwnPassword(apiRequestBodyAsJson);
        return this.toApiJsonSerializer.serialize(result);
    }

    @POST
    @Path("first-time-password-change")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public Response firstTimePasswordChange(final FirstTimePasswordChangeRequest request) {
        try {
            // 1. Manually authenticate using the temp password
            AppUser user = appUserRepository.findByUsername(request.getUsername())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));

            // 2. Find the user
            if (!passwordEncoder.matches(request.getAutoGeneratedPassword(), user.getPassword())) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity("Invalid username or temporary password").build();
            }

            // 3. Check password match
            if (!request.getNewPassword().equals(request.getConfirmPassword())) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Passwords do not match").build();
            }

            // 4. Validate password strength
            try {
                PasswordValidator.validate(request.getNewPassword());
            } catch (PlatformApiDataValidationException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(e.getErrors()).build();
            }

            // 5. Update password
            String encodedPassword = passwordEncoder.encode(request.getNewPassword());
            user.updatePasswordOnly(encodedPassword);

            // Optional: Reset login status
            user.setAccountNonLocked(true);
            user.setFailedLoginAttempts(0);
            user.setCredentialsLockedAt(null);
            //user.setFirstTimeLogin(false); // Uncomment if using

            appUserRepository.save(user); // Save changes

            return Response.ok("Password changed successfully. You can now log in.").build();
        } catch (BadCredentialsException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid username or temporary password").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Unexpected error: " + e.getMessage()).build();
        }
    }

    @POST
    @Path("unlock-user")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public Response secureUnlockUserByUsername(final Map<String, String> request) {
        String adminUsername = request.get("adminUsername");
        String adminPassword = request.get("adminPassword");
        String targetUsername = request.get("targetUsername");

        if (adminUsername == null || adminPassword == null || targetUsername == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("adminUsername, adminPassword, and targetUsername are required").build();
        }

        try {
            // Authenticate admin user
            UsernamePasswordAuthenticationToken authRequest =
                    new UsernamePasswordAuthenticationToken(adminUsername, adminPassword);
            authenticationManager.authenticate(authRequest);

            // Find target user
            Optional<AppUser> optionalTargetUser = appUserRepository.findByUsername(targetUsername);
            if (optionalTargetUser.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND).entity("Target user not found").build();
            }

            AppUser targetUser = optionalTargetUser.get();

            if (!targetUser.isAccountNonLocked()) {
                targetUser.setAccountNonLocked(true);
                targetUser.setFailedLoginAttempts(0);
                targetUser.setCredentialsLockedAt(null);
                appUserRepository.save(targetUser);
                return Response.ok("User '" + targetUsername + "' unlocked successfully by '" + adminUsername + "'").build();
            } else {
                return Response.status(Response.Status.BAD_REQUEST).entity("User is not locked").build();
            }
        } catch (BadCredentialsException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Authentication failed for admin user").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Unexpected error: " + e.getMessage()).build();
        }
    }

    @POST
    @Path("forgot-password/request")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public Response requestForgotPasswordOtp(Map<String, String> request) {
        String username = request.get("username");
        String email = request.get("email");

        if (username == null || email == null || username.isBlank() || email.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Both 'username' and 'email' are required")).build();
        }

        Optional<AppUser> optionalUser = appUserRepository.findByUsername(username);
        if (optionalUser.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "User not found")).build();
        }

        AppUser user = optionalUser.get();

        if (!user.getEmail().equalsIgnoreCase(email)) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Email does not match")).build();
        }

        // Generate OTP and store it temporarily (for testing, use static map)
        String otp = String.valueOf((int)(100000 + Math.random() * 900000)); // 6-digit OTP
        otpCache.put(username, new OtpEntry(otp, Instant.now()));

        // Return OTP in response for now (don't do this in production!)
        return Response.ok(Map.of(
                "message", "OTP generated successfully",
                "otp", otp
        )).build();
    }

    @POST
    @Path("forgot-password/verify")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public Response verifyOtpAndResetPassword(Map<String, String> request) {
        String username = request.get("username");
        String otp = request.get("otp");
        String newPassword = request.get("newPassword");
        String confirmPassword = request.get("confirmPassword");

        if (username == null || otp == null || newPassword == null || confirmPassword == null ||
                username.isBlank() || otp.isBlank() || newPassword.isBlank() || confirmPassword.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "All fields (username, otp, newPassword, confirmPassword) are required")).build();
        }

        Optional<AppUser> optionalUser = appUserRepository.findByUsername(username);
        if (optionalUser.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "User not found")).build();
        }

        AppUser user = optionalUser.get();

        // Check OTP
        OtpEntry otpEntry = otpCache.get(username);
        if (otpEntry == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No OTP request found for this user")).build();
        }

        if (!otpEntry.getOtp().equals(otp)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid OTP")).build();
        }

        // Check if OTP is expired (valid for 10 minutes)
        if (Duration.between(otpEntry.getGeneratedAt(), Instant.now()).toMinutes() > 10) {
            otpCache.remove(username);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "OTP expired")).build();
        }

        // Check if passwords match
        if (!newPassword.equals(confirmPassword)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Passwords do not match")).build();
        }

        // 💪 Validate password strength
        try {
            PasswordValidator.validate(newPassword);
        } catch (PlatformApiDataValidationException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Weak password", "details", e.getErrors())).build();
        }

        // Update the password
        String encodedPassword = passwordEncoder.encode(newPassword);
        user.updatePasswordOnly(encodedPassword);

        // Lock account for 5 minutes
        user.setAccountNonLocked(false);
        user.setCredentialsLockedAt(LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()));
        user.setFailedLoginAttempts(0);

        appUserRepository.save(user);
        otpCache.remove(username);

        // Log to console
        System.out.println("✅ Password reset successful for user: " + username);
        System.out.println("🔒 Account temporarily locked for 5 minutes after password reset.");

        return Response.ok(Map.of(
                "message", "Password reset successful. Account is temporarily locked for 5 minutes for security."
        )).build();
    }

}
