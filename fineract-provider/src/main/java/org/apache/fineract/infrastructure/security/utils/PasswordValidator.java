package org.apache.fineract.infrastructure.security.utils;

import java.util.ArrayList;
import java.util.List;

import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;

public class PasswordValidator {

    public static void validate(String password) {
        List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        DataValidatorBuilder validatorBuilder = new DataValidatorBuilder(dataValidationErrors).resource("user");

        validatorBuilder.reset().parameter("newPassword").value(password).notBlank();

        if (password != null) {
            if (password.length() < 8) {
                validatorBuilder.reset().parameter("newPassword").value(password)
                        .failWithCode("password.too.short", "Password must be at least 8 characters.");
            }

            if (!password.matches(".*[A-Z].*")) {
                validatorBuilder.reset().parameter("newPassword").value(password)
                        .failWithCode("password.no.uppercase", "Password must contain at least one uppercase letter.");
            }

            if (!password.matches(".*[a-z].*")) {
                validatorBuilder.reset().parameter("newPassword").value(password)
                        .failWithCode("password.no.lowercase", "Password must contain at least one lowercase letter.");
            }

            if (!password.matches(".*\\d.*")) {
                validatorBuilder.reset().parameter("newPassword").value(password)
                        .failWithCode("password.no.digit", "Password must contain at least one number.");
            }

            if (!password.matches(".*[!@#$%^&*()_+=\\-{}|:;\"'<>,.?/~`].*")) {
                validatorBuilder.reset().parameter("newPassword").value(password)
                        .failWithCode("password.no.specialchar", "Password must contain at least one special character.");
            }
        }

        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException("error.msg.password.weak", "Password does not meet strength requirements.", dataValidationErrors);
        }
    }
}
