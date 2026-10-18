package com.bank.lending.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IamApplication {
    public static void main(String[] args) {
        // timestamps without a zone (the authorization tables) are written and read in UTC
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication.run(IamApplication.class, args);
    }
}
