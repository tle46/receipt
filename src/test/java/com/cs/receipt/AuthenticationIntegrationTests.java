package com.cs.receipt;

import com.cs.receipt.auth.*;
import com.cs.receipt.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class AuthenticationIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @MockitoBean IdentityProviders providers;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    String ip;
    @BeforeEach void setup() {
        ip=UUID.randomUUID().toString();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(get("/").with(r -> {r.setRemoteAddr(ip);return r;})).build();
    }
    private String postJson(String path,String json,String access,int status) throws Exception {
        var request=post("/api/auth/"+path).contentType(MediaType.APPLICATION_JSON).content(json);
        if(access!=null) request.header("Authorization","Bearer "+access);
        return mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
    private String field(String json,String field) { return JsonPath.read(json,"$."+field); }
    private String guest() throws Exception { return postJson("guest","{}",null,201); }
    private String registration(String name) {
        return "{\"username\":\""+name+"\",\"password\":\"correct horse battery\",\"email\":\""+name+"@example.com\",\"displayName\":\"Test Person\"}";
    }
    private String name() { return "u"+UUID.randomUUID().toString().replace("-", "").substring(0,20); }
    @Test void guestUpgradeKeepsReceiptsAndRevokesGuestSession() throws Exception {
        String guest=guest(), access=field(guest,"accessToken");
        Number id=JsonPath.read(guest,"$.user.id");
        mvc.perform(post("/api/receipts").param("userId",id.toString()).header("Authorization","Bearer "+access)
                .contentType(MediaType.APPLICATION_JSON).content("{\"merchantName\":\"Cafe\",\"currency\":\"USD\",\"discount\":0,\"tax\":0,\"fee\":0,\"tip\":0,\"total\":10,\"items\":[{\"name\":\"Food\",\"quantity\":1,\"unitPrice\":10,\"total\":10}]}"))
                .andExpect(status().isCreated());
        String upgraded=postJson("register",registration(name()),access,201);
        assertThat((Number)JsonPath.read(upgraded,"$.user.id")).isEqualTo(id);
        mvc.perform(get("/api/receipts").param("userId",id.toString()).header("Authorization","Bearer "+field(upgraded,"accessToken")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
    }
    @Test void protectsActorsAndDisablesLegacyAccountCreation() throws Exception {
        String guest=guest(), access=field(guest,"accessToken");
        mvc.perform(get("/api/receipts").param("userId","999999")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/receipts").param("userId","999999").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
        mvc.perform(get("/api/users/999999/friends").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
        mvc.perform(post("/api/users").header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void rotatesRefreshTokensAndLogoutImmediatelyRevokesAccess() throws Exception {
        String guest=guest(), refresh=field(guest,"refreshToken");
        String next=postJson("refresh","{\"refreshToken\":\""+refresh+"\"}",null,200);
        postJson("refresh","{\"refreshToken\":\""+refresh+"\"}",null,401);
        postJson("logout","{}",field(next,"accessToken"),204);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+field(guest,"accessToken"))).andExpect(status().isUnauthorized());
        postJson("refresh","{\"refreshToken\":\""+field(next,"refreshToken")+"\"}",null,401);
    }
    @Test void passwordsAreHashedLoginIsCaseInsensitiveAndChangeRevokesAllSessions() throws Exception {
        String name=name(), account=postJson("register",registration(name),null,201);
        assertThat(users.findByUsernameIgnoreCase(name).orElseThrow().getPasswordHash()).startsWith("$2").doesNotContain("correct");
        String login=postJson("login","{\"username\":\""+name.toUpperCase()+"\",\"password\":\"correct horse battery\"}",null,200);
        postJson("login","{\"username\":\""+name+"\",\"password\":\"wrong password\"}",null,401);
        postJson("password/change","{\"currentPassword\":\"correct horse battery\",\"newPassword\":\"another long password\"}",field(login,"accessToken"),204);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+field(account,"accessToken"))).andExpect(status().isUnauthorized());
        postJson("login","{\"username\":\""+name+"\",\"password\":\"another long password\"}",null,200);
    }
    @Test void emailVerificationAndRecoveryAreSingleUse() throws Exception {
        String name=name(), account=postJson("register",registration(name),null,201);
        postJson("email/send-verification","{}",field(account,"accessToken"),202);
        var messages=ArgumentCaptor.forClass(SimpleMailMessage.class); verify(mail).send(messages.capture());
        String verifyToken=messages.getValue().getText().split("\n\n")[1];
        postJson("email/verify","{\"token\":\""+verifyToken+"\"}",null,204);
        postJson("email/verify","{\"token\":\""+verifyToken+"\"}",null,401);
        postJson("password/forgot","{\"email\":\""+name+"@example.com\"}",null,202);
        verify(mail,times(2)).send(messages.capture());
        String reset=messages.getValue().getText().split("\n\n")[1];
        String request="{\"token\":\""+reset+"\",\"password\":\"replacement password\"}";
        postJson("password/reset",request,null,204); postJson("password/reset",request,null,401);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+field(account,"accessToken"))).andExpect(status().isUnauthorized());
    }
    @Test void googleUpgradeUsesBoundSingleUseNonce() throws Exception {
        String guest=guest(), access=field(guest,"accessToken");
        String nonce=field(postJson("google/nonce","{}",access,200),"nonce");
        Jwt google=Jwt.withTokenValue("verified").header("alg","RS256").subject(name())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(providers.google("verified",nonce)).thenReturn(google);
        String body="{\"idToken\":\"verified\",\"nonce\":\""+nonce+"\"}";
        postJson("google",body,null,401);
        String result=postJson("google",body,access,200);
        assertThat((Number)JsonPath.read(result,"$.user.id")).isEqualTo((Number)JsonPath.read(guest,"$.user.id"));
        postJson("google",body,field(result,"accessToken"),401);
    }
    @Test void deactivationInvalidatesTokens() throws Exception {
        String account=guest(), access=field(account,"accessToken");
        mvc.perform(delete("/api/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
        postJson("refresh","{\"refreshToken\":\""+field(account,"refreshToken")+"\"}",null,401);
    }
    @Test void rejectsMalformedAndTamperedTokensAndWeakPasswords() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        postJson("register",registration(name()).replace("correct horse battery","short"),null,400);
    }
    @Test void rejectsExpiredSessionAndTokensWithInvalidSignatures() throws Exception {
        String account=guest(), access=field(account,"accessToken");
        // Change the signature while retaining a structurally valid JWT.
        int signature=access.lastIndexOf('.')+1;
        String tampered=access.substring(0,signature)+(access.charAt(signature)=='A' ? "B" : "A")+access.substring(signature+1);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+tampered)).andExpect(status().isUnauthorized());
        Number id=JsonPath.read(account,"$.user.id");
        var repository=context.getBean(AuthSessionRepository.class);
        var session=repository.findByUserId(id.longValue()).getFirst();
        session.expiresAt=Instant.now().minusSeconds(1); repository.save(session);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
        postJson("refresh","{\"refreshToken\":\""+field(account,"refreshToken")+"\"}",null,401);
    }
    @Test void rateLimitsRepeatedLoginFailures() throws Exception {
        String body="{\"username\":\""+name()+"\",\"password\":\"wrong password\"}";
        for(int i=0;i<10;i++) postJson("login",body,null,401);
        postJson("login",body,null,429);
    }
    @Test void duplicateActorParametersCannotBypassAuthorization() throws Exception {
        String account=guest(), access=field(account,"accessToken");
        Number id=JsonPath.read(account,"$.user.id");
        mvc.perform(get("/api/receipts").param("userId",id.toString(),"999999").header("Authorization","Bearer "+access))
                .andExpect(status().isForbidden());
    }
}
