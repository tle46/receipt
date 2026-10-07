package com.cs.receipt.auth;

import com.cs.receipt.model.UserStatus;
import com.cs.receipt.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.time.Instant;

@Configuration
public class SecurityConfig {
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<AuthRateLimiter> rateLimiterRegistration(AuthRateLimiter limiter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(limiter);
        registration.setEnabled(false);
        return registration;
    }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecretKeySpec jwtKey(@Value("${app.auth.jwt-secret}") String value) {
        byte[] key = Base64.getDecoder().decode(value);
        if (key.length < 32) throw new IllegalStateException("JWT secret must contain at least 32 random bytes (Base64 encoded)");
        return new SecretKeySpec(key, "HmacSHA256");
    }
    @Bean JwtEncoder jwtEncoder(SecretKeySpec key) { return NimbusJwtEncoder.withSecretKey(key).build(); }
    @Bean JwtDecoder jwtDecoder(SecretKeySpec key, AuthSessionRepository sessions, UserRepository users) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("receipt-api"), jwt -> {
            try {
                var session = sessions.findById(jwt.getClaimAsString("sid")).orElseThrow();
                var user = users.findById(session.userId).orElseThrow();
                if (!session.revoked && session.expiresAt.isAfter(Instant.now())
                        && session.userId.toString().equals(jwt.getSubject()) && jwt.getAudience().contains("receipt-api")
                        && (user.getStatus() == UserStatus.ACTIVE || user.getStatus() == UserStatus.GUEST)) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (RuntimeException ignored) { }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Session is no longer valid", null));
        }));
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, AuthRateLimiter limiter) throws Exception {
        return http.cors(c -> {}).csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/guest", "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/google/nonce", "/api/auth/google",
                                "/api/auth/password/forgot", "/api/auth/password/reset", "/api/auth/email/verify").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/error").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/users").denyAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint((req,res,e) -> {
                    res.setStatus(401); res.setContentType("application/json"); res.getWriter().write("{\"message\":\"Authentication required\"}");
                }))
                .addFilterBefore(limiter, BearerTokenAuthenticationFilter.class)

                .build();
    }
}
