package com.cs.receipt.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.oauth2.jwt.*;

@Service
public class IdentityProviders {
    private final String googleClient;
    private final JwtDecoder google = googleDecoder();

    private static JwtDecoder googleDecoder() {
        var decoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://accounts.google.com"));
        return decoder;
    }

    public IdentityProviders(@Value("${app.auth.google-client-id:}") String googleClient) {
        this.googleClient = googleClient;
    }

    public Jwt google(String idToken, String nonce) {
        if (googleClient.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google sign-in is not configured");
        try {
            Jwt jwt = google.decode(idToken);
            if (!jwt.getAudience().contains(googleClient) || !nonce.equals(jwt.getClaimAsString("nonce"))
                    || (jwt.hasClaim("azp") && !googleClient.equals(jwt.getClaimAsString("azp")))) throw new IllegalArgumentException();
            return jwt;
        } catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google credential"); }
    }
}
