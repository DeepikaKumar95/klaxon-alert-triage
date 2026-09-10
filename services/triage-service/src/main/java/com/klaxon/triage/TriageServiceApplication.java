package com.klaxon.triage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TriageServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TriageServiceApplication.class, args);
    }
}
