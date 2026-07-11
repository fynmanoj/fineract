package org.apache.fineract;

import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

public class TempPasswordGenerator {

    public static void main(String[] args) {

        PasswordEncoder encoder =
                PasswordEncoderFactories.createDelegatingPasswordEncoder();

        String hash = encoder.encode("Template@123");

        System.out.println(hash);
    }
}