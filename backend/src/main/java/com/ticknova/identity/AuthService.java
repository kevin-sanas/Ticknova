package com.ticknova.identity;

import com.ticknova.audit.AuditService;
import com.ticknova.shared.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final IdentityRepository users;
    private final SessionTokens tokens;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final String dummyHash;

    public AuthService(IdentityRepository users,SessionTokens tokens,PasswordEncoder encoder,AuditService audit) {
        this.users=users; this.tokens=tokens; this.encoder=encoder; this.audit=audit;
        this.dummyHash=encoder.encode(UUID.randomUUID().toString()+UUID.randomUUID());
    }

    public LoginResult login(String username,String password) {
        var account=users.findByUsername(username);
        boolean matches=matches(password,account.map(IdentityRepository.Account::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !account.get().active() || !matches) {
            audit.record(null,"AUTH_LOGIN_FAILED","AUTH",null,Map.of("reason","INVALID_CREDENTIALS"));
            throw new ApiException(401,"INVALID_CREDENTIALS","用户名或密码错误");
        }
        var user=account.get();
        var actor=new Actor(user.id(),user.username(),user.displayName(),users.roles(user.id()),user.mustChangePassword());
        var generated=tokens.generate();
        Instant expiresAt=Instant.now().plus(Duration.ofMinutes(30));
        if (!users.createSessionIfPasswordCurrent(user.id(),user.passwordHash(),generated.hash(),expiresAt)) {
            audit.record(null,"AUTH_LOGIN_FAILED","AUTH",null,Map.of("reason","CREDENTIALS_CHANGED"));
            throw new ApiException(401,"INVALID_CREDENTIALS","用户名或密码错误");
        }
        audit.record(actor,"AUTH_LOGIN","USER",user.id(),Map.of());
        return new LoginResult(generated.token(),expiresAt,actor);
    }

    public Actor authenticate(String token) {
        if (token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw unauthenticated();
        return users.sessionActor(tokens.hash(token)).orElseThrow(AuthService::unauthenticated);
    }

    public void authenticationRejected() {
        audit.record(null,"AUTH_SESSION_REJECTED","AUTH",null,Map.of("reason","INVALID_SESSION"));
    }

    @Transactional
    public void logout(Actor actor,String token) {
        users.revokeSession(actor.id(),tokens.hash(token));
        audit.record(actor,"AUTH_LOGOUT","USER",actor.id(),Map.of());
    }

    @Transactional
    public void changePassword(Actor actor,String oldPassword,String newPassword) {
        PasswordPolicy.validate(newPassword);
        var user=users.findById(actor.id()).orElseThrow(AuthService::unauthenticated);
        if (!user.active() || !matches(oldPassword,user.passwordHash()))
            throw new ApiException(400,"INVALID_CURRENT_PASSWORD","当前密码不正确");
        if (encoder.matches(newPassword,user.passwordHash()))
            throw new ApiException(400,"PASSWORD_UNCHANGED","新密码必须与当前密码不同");
        if (!users.changePassword(actor.id(),user.passwordHash(),encoder.encode(newPassword)))
            throw new ApiException(409,"PASSWORD_CHANGED","密码已变更，请重新登录");
        audit.record(actor,"AUTH_PASSWORD_CHANGED","USER",actor.id(),Map.of("sessionsRevoked",true));
    }

    private boolean matches(String rawPassword,String hash) {
        return rawPassword!=null && rawPassword.getBytes(StandardCharsets.UTF_8).length<=72 && encoder.matches(rawPassword,hash);
    }

    private static ApiException unauthenticated() { return new ApiException(401,"UNAUTHENTICATED","请重新登录"); }
    public record LoginResult(String token,Instant expiresAt,Actor user) {}
}
