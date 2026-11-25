package com.jobscheduler.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Scheduler and worker instances serve no users; their HTTP port exists only for health checks.
 * Everything except /actuator/health is denied.
 */
@Configuration
@Profile("!api")
public class InternalSecurityConfig {

    @Bean
    public SecurityFilterChain internalSecurity(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
