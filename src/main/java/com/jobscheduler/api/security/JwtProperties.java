package com.jobscheduler.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param secret HS256 key shared by every API instance, so any instance can verify any token.
 *               At least 32 bytes; comes from the APP_JWT_SECRET environment variable.
 * @param ttl    token lifetime; short, because a JWT cannot be revoked before it expires
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("1h") Duration ttl,
        @DefaultValue("job-scheduler") String issuer) {
}
