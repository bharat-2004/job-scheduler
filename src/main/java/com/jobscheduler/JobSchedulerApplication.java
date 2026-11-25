package com.jobscheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One application, three roles. Start it with spring.profiles.active = api, scheduler, worker
 * (or any combination); each role's beans are guarded by @Profile.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class JobSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobSchedulerApplication.class, args);
    }
}
