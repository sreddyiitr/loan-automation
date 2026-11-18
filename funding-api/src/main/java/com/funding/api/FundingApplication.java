package com.funding.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** One Spring Boot app runs the start API, the Temporal workers and the trigger relay (plan "Modules"). */
@SpringBootApplication
@EnableScheduling
public class FundingApplication {
    public static void main(String[] args) {
        SpringApplication.run(FundingApplication.class, args);
    }
}
