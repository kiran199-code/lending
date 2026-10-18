package com.bank.lending.lcm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LcmApplication {
    public static void main(String[] args) {
        SpringApplication.run(LcmApplication.class, args);
    }
}
