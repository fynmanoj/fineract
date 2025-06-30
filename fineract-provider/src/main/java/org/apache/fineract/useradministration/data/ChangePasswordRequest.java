package org.apache.fineract.useradministration.data;

import jakarta.validation.constraints.NotBlank;

public class ChangePasswordRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String currentPassword;

    @NotBlank
    private String newPassword;

    @NotBlank
    private String confirmPassword;

    public ChangePasswordRequest(String username, String currentPassword, String newPassword, String confirmPassword){

        this.username = username;
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
        this.confirmPassword = confirmPassword;
    }

    public String getUsername(){
        return username;
    }

    public String getCurrentPassword(){
        return currentPassword;
    }

    public String getNewPassword(){
        return newPassword;
    }

    public String getConfirmPassword(){
        return confirmPassword;
    }

    public void setUsername(String username){
        this.username = username;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

}


