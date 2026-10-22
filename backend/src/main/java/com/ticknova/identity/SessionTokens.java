package com.ticknova.identity;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class SessionTokens {
    private final byte[] pepper;
    private final SecureRandom random = new SecureRandom();

    public SessionTokens(@Value("${ticknova.session-pepper}") String pepper) {
        if (pepper==null || pepper.getBytes(StandardCharsets.UTF_8).length<32)
            throw new IllegalArgumentException("SESSION_TOKEN_PEPPER must contain at least 32 bytes");
        this.pepper=pepper.getBytes(StandardCharsets.UTF_8);
    }

    public GeneratedToken generate() {
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedToken(token,hash(token));
    }

    public String hash(String token) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Session hash unavailable"); }
    }

    public record GeneratedToken(String token,String hash) {}
}
