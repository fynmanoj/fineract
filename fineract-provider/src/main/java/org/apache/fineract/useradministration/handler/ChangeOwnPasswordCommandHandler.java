package org.apache.fineract.useradministration.handler;

import org.apache.fineract.commands.annotation.CommandType;
import org.apache.fineract.commands.handler.NewCommandSourceHandler;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.useradministration.data.ChangePasswordRequest;
import org.apache.fineract.useradministration.service.AppUserWritePlatformService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.gson.Gson;

@Component
@CommandType(entity = "USER", action = "CHANGE_PASSWORD")
public class ChangeOwnPasswordCommandHandler implements NewCommandSourceHandler {

    private final AppUserWritePlatformService appUserWritePlatformService;

    @Autowired
    public ChangeOwnPasswordCommandHandler(AppUserWritePlatformService appUserWritePlatformService) {
        this.appUserWritePlatformService = appUserWritePlatformService;
    }

    @Override
    public CommandProcessingResult processCommand(final JsonCommand command) {
        // Parse the JSON into your DTO (ChangePasswordRequest)
        final ChangePasswordRequest request = new Gson().fromJson(command.json(), ChangePasswordRequest.class);

        return this.appUserWritePlatformService.changeOwnPassword(request);
    }
}
