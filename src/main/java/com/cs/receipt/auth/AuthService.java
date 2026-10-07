package com.cs.receipt.auth;

import com.cs.receipt.model.*;
import com.cs.receipt.repository.UserRepository;
import com.cs.receipt.exception.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import java.time.Instant;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

@Service
@Transactional
public class AuthService {
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final AccountTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final IdentityProviders providers;
    private final AuthRateLimiter limiter;
    private final ObjectProvider<JavaMailSender> mail;
    private final String mailFrom;
    private final String dummyHash;
    public AuthService(UserRepository users, AuthSessionRepository sessions, AccountTokenRepository tokens,
                       PasswordEncoder passwords, JwtEncoder encoder, IdentityProviders providers, AuthRateLimiter limiter,
                       ObjectProvider<JavaMailSender> mail, @Value("${app.auth.mail-from:noreply@example.com}") String mailFrom) {
        this.users=users; this.sessions=sessions; this.tokens=tokens; this.passwords=passwords;
        this.encoder=encoder; this.providers=providers; this.limiter=limiter; this.mail=mail; this.mailFrom=mailFrom;
        dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    public record Credentials(String accessToken, String refreshToken, String tokenType, long expiresIn, AccountResponse user) {}
    public static String randomToken() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials or expired token"); }
    public User user(Long id) {
        User u = users.findForAuthentication(id).orElseThrow(this::unauthorized);
        if (u.getStatus()!=UserStatus.ACTIVE && u.getStatus()!=UserStatus.GUEST) throw unauthorized();
        return u;
    }
    private User newUser(UserStatus status) {
        User u = new User(); u.setUsername("user-" + UUID.randomUUID()); u.setDisplayName("Guest"); u.setStatus(status);
        return users.save(u);
    }
    public Credentials guest() { return issue(newUser(UserStatus.GUEST)); }
    public Credentials register(AuthRequests.Register request, Long guestId) {
        String username=request.username().toLowerCase(Locale.ROOT), email=request.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByUsernameIgnoreCase(username).isPresent() || users.existsByEmail(email))
            throw new ConflictException("Username or email is unavailable; sign in to an existing account instead");
        User u = guestId == null ? newUser(UserStatus.GUEST) : user(guestId);
        if (u.getStatus()!=UserStatus.GUEST) throw new ConflictException("Account is already registered");
        u.setUsername(username); u.setEmail(email); u.setDisplayName(request.displayName().trim());
        u.setPasswordHash(encodePassword(request.password())); u.setStatus(UserStatus.ACTIVE);
        users.saveAndFlush(u); revokeAll(u.getId()); return issue(u);
    }
    private String encodePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length>72) throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes");
        return passwords.encode(password);
    }
    public Credentials login(AuthRequests.Login request) {
        String identifier = request.username().trim().toLowerCase(Locale.ROOT);
        // Registration usernames cannot contain @, so email lookup is unambiguous.
        User u = (identifier.contains("@") ? users.findByEmailIgnoreCase(identifier)
                : users.findByUsernameIgnoreCase(identifier)).orElse(null);
        // Both identifiers share the same attempt budget for an existing account.
        limit(u == null ? "login:identifier:" + identifier : "login:account:" + u.getId(), 10);
        boolean valid=passwords.matches(request.password(), u==null || u.getPasswordHash()==null ? dummyHash : u.getPasswordHash());
        if (!valid || u==null || u.getPasswordHash()==null || u.getStatus()!=UserStatus.ACTIVE) throw unauthorized();
        return issue(u);
    }
    private Credentials issue(User u) {
        AuthSession s=new AuthSession(); s.id=UUID.randomUUID().toString(); s.userId=u.getId();
        s.expiresAt=Instant.now().plusSeconds(30*24*3600L);
        String refresh=randomToken(); s.refreshHash=hash(refresh); sessions.save(s);
        return credentials(u,s,refresh);
    }
    private Credentials credentials(User u, AuthSession s, String refresh) {
        Instant now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer("receipt-api").subject(u.getId().toString()).audience(List.of("receipt-api"))
                .issuedAt(now).expiresAt(now.plusSeconds(900)).id(UUID.randomUUID().toString()).claim("sid",s.id).build();
        String access=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        return new Credentials(access,refresh,"Bearer",900,AccountResponse.from(u));
    }
    public Credentials refresh(String token) {
        AuthSession s=sessions.findByRefreshHash(hash(token)).orElseThrow(this::unauthorized);
        if (s.revoked || !s.expiresAt.isAfter(Instant.now())) throw unauthorized();
        User u=user(s.userId); String next=randomToken(); s.refreshHash=hash(next); sessions.saveAndFlush(s);
        return credentials(u,s,next);
    }
    public void logout(String sessionId) { sessions.findById(sessionId).ifPresent(s -> { s.revoked=true; sessions.save(s); }); }
    public void revokeAll(Long userId) { sessions.findByUserId(userId).forEach(s -> { s.revoked=true; sessions.save(s); }); }
    public void profile(Long id, String name) { user(id).setDisplayName(name.trim()); }
    public void changePassword(Long id, AuthRequests.Password request) {
        User u=user(id);
        if(u.getPasswordHash()==null || !passwords.matches(request.currentPassword(),u.getPasswordHash())) throw unauthorized();
        u.setPasswordHash(encodePassword(request.newPassword())); revokeAll(id);
    }
    public void deactivate(Long id) { user(id).setStatus(UserStatus.DISABLED); revokeAll(id); }
    public void limit(String key, int count) {
        if (!limiter.allow(key,count)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Try again later");
    }
    public String googleNonce(Long id) { return token(id, "GOOGLE", null, 300); }
    public Credentials google(AuthRequests.Google request, Long currentId) {
        var jwt=providers.google(request.idToken(),request.nonce());
        AccountToken nonce=consume(request.nonce(),"GOOGLE");
        if (!Objects.equals(nonce.userId,currentId)) throw unauthorized();
        User existing=users.findByGoogleSubject(jwt.getSubject()).orElse(null);
        User u=identityUser(existing,currentId);
        if(u.getGoogleSubject()!=null && !u.getGoogleSubject().equals(jwt.getSubject())) throw new ConflictException("Google identity already linked");
        u.setGoogleSubject(jwt.getSubject());
        // Never auto-link an existing account by email alone.
        String email=jwt.getClaimAsString("email");
        if(u.getEmail()==null && Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")) && email!=null
                && !users.existsByEmail(email.toLowerCase(Locale.ROOT))) {
            u.setEmail(email.toLowerCase(Locale.ROOT)); u.setEmailVerified(true);
        }
        return finishIdentity(u);
    }
    private User identityUser(User existing, Long currentId) {
        if(existing!=null) {
            user(existing.getId());
            if(currentId!=null && !existing.getId().equals(currentId))
                throw new ConflictException("Identity belongs to another account. Sign in separately; guest data has not been moved.");
            return existing;
        }
        return currentId==null ? newUser(UserStatus.GUEST) : user(currentId);
    }
    private Credentials finishIdentity(User u) {
        boolean guest=u.getStatus()==UserStatus.GUEST; u.setStatus(UserStatus.ACTIVE);
        users.saveAndFlush(u); if(guest) revokeAll(u.getId()); return issue(u);
    }
    private String token(Long id,String purpose,String email,long lifetime) {
        String raw=randomToken(); AccountToken t=new AccountToken(); t.hash=hash(raw); t.userId=id;
        t.purpose=purpose; t.email=email; t.expiresAt=Instant.now().plusSeconds(lifetime); tokens.save(t); return raw;
    }
    private AccountToken consume(String raw,String purpose) {
        AccountToken t=tokens.findByHash(hash(raw)).orElseThrow(this::unauthorized);
        if(!purpose.equals(t.purpose) || !t.expiresAt.isAfter(Instant.now())) throw unauthorized();
        tokens.delete(t); tokens.flush(); return t;
    }
    public void forgot(String email) {
        String normalized=email.trim().toLowerCase(Locale.ROOT); limit("email:"+normalized,3);
        requireMail();
        users.findByEmail(normalized).filter(u -> u.getStatus()==UserStatus.ACTIVE && u.isEmailVerified() && u.getPasswordHash()!=null)
                .ifPresent(u -> send(u.getEmail(),"Reset your password",token(u.getId(),"RESET",u.getEmail(),900)));
    }
    public void reset(AuthRequests.Reset request) {
        AccountToken t=consume(request.token(),"RESET"); User u=user(t.userId);
        if(!u.isEmailVerified() || !Objects.equals(u.getEmail(),t.email)) throw unauthorized();
        u.setPasswordHash(encodePassword(request.password())); revokeAll(u.getId());
        // Outstanding recovery links must not survive a successful reset.
        tokens.deleteByUserIdAndPurpose(u.getId(), "RESET");
    }
    public void verification(Long id) {
        User u=user(id); if(u.getEmail()==null) throw new IllegalArgumentException("No email on account");
        limit("email:"+u.getEmail(),3);
        if(!u.isEmailVerified()) send(u.getEmail(),"Verify your email",token(id,"EMAIL",u.getEmail(),3600));
    }
    public void verifyEmail(String raw) {
        AccountToken t=consume(raw,"EMAIL"); User u=user(t.userId);
        if(!Objects.equals(u.getEmail(),t.email)) throw unauthorized(); u.setEmailVerified(true);
    }
    private JavaMailSender requireMail() {
        JavaMailSender sender=mail.getIfAvailable();
        if(sender==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Email delivery is not configured");
        return sender;
    }
    private void send(String email,String subject,String raw) {
        SimpleMailMessage message=new SimpleMailMessage(); message.setFrom(mailFrom); message.setTo(email);
        message.setSubject(subject); message.setText(subject + " in the app using this one-time token:\n\n"+raw+"\n\nIf you did not request this, ignore this email.");
        try { requireMail().send(message); }
        catch(org.springframework.mail.MailException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Email delivery unavailable"); }
    }
}
