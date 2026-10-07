package com.cs.receipt.auth;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    private Long id(Jwt jwt) { return jwt == null ? null : Long.valueOf(jwt.getSubject()); }
    @PostMapping("/guest") @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Credentials guest() { return auth.guest(); }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Credentials register(@Valid @RequestBody AuthRequests.Register request, @AuthenticationPrincipal Jwt jwt) {
        return auth.register(request,id(jwt));
    }
    @PostMapping("/login")
    public AuthService.Credentials login(@Valid @RequestBody AuthRequests.Login request) { return auth.login(request); }
    @PostMapping("/refresh")
    public AuthService.Credentials refresh(@Valid @RequestBody AuthRequests.Refresh request) { return auth.refresh(request.refreshToken()); }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt) { auth.logout(jwt.getClaimAsString("sid")); }
    @PostMapping("/logout-all") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal Jwt jwt) { auth.revokeAll(id(jwt)); }
    @GetMapping("/me")
    public AccountResponse me(@AuthenticationPrincipal Jwt jwt) { return AccountResponse.from(auth.user(id(jwt))); }
    @PutMapping("/me")
    public AccountResponse profile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AuthRequests.Profile request) {
        auth.profile(id(jwt),request.displayName()); return AccountResponse.from(auth.user(id(jwt)));
    }
    @DeleteMapping("/me") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal Jwt jwt) { auth.deactivate(id(jwt)); }
    @PostMapping("/google/nonce")
    public Map<String,String> nonce(@AuthenticationPrincipal Jwt jwt) { return Map.of("nonce",auth.googleNonce(id(jwt))); }
    @PostMapping("/google")
    public AuthService.Credentials google(@Valid @RequestBody AuthRequests.Google request, @AuthenticationPrincipal Jwt jwt) {
        return auth.google(request,id(jwt));
    }
    @PostMapping("/password/change") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AuthRequests.Password request) { auth.changePassword(id(jwt),request); }
    @PostMapping("/password/forgot") @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgot(@Valid @RequestBody AuthRequests.EmailRequest request) { auth.forgot(request.email()); }
    @PostMapping("/password/reset") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody AuthRequests.Reset request) { auth.reset(request); }
    @PostMapping("/email/send-verification") @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendVerification(@AuthenticationPrincipal Jwt jwt) { auth.verification(id(jwt)); }
    @PostMapping("/email/verify") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody AuthRequests.Token request) { auth.verifyEmail(request.token()); }
}
