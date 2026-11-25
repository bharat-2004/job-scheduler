package com.jobscheduler.api;

import com.jobscheduler.api.dto.ApiDtos.TokenResponse;
import com.jobscheduler.api.dto.ApiDtos.UserResponse;
import com.jobscheduler.api.security.JwtProperties;
import com.jobscheduler.api.web.ApiExceptions.EmailAlreadyRegisteredException;
import com.jobscheduler.api.web.ApiExceptions.InvalidCredentialsException;
import com.jobscheduler.common.domain.User;
import com.jobscheduler.common.repository.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

@Service
@Profile("api")
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProps;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Compared against for unknown emails, so both failure paths take bcrypt time (no user enumeration by timing). */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
                       JwtProperties jwtProps, TransactionTemplate tx, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProps = jwtProps;
        this.tx = tx;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    public UserResponse register(String email, String password) {
        String normalized = User.normalizeEmail(email);
        if (users.existsByEmail(normalized)) {
            throw new EmailAlreadyRegisteredException();
        }
        User user = User.register(normalized, passwordEncoder.encode(password), clock.instant());
        try {
            tx.executeWithoutResult(s -> users.insert(user));
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyRegisteredException(); // lost a registration race; the unique constraint decided
        }
        return new UserResponse(user.getId(), user.getEmail(), user.getCreatedAt());
    }

    public TokenResponse login(String email, String password) {
        User user = users.findByEmail(User.normalizeEmail(email)).orElse(null);
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(password, hash);
        if (user == null || !matches) {
            throw new InvalidCredentialsException();
        }
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProps.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProps.ttl()))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new TokenResponse(token, "Bearer", jwtProps.ttl().toSeconds());
    }
}
