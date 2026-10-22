package com.ticknova.identity;

import com.ticknova.shared.ApiException;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LoginRateLimiterTest {
    @Test void boundsRepeatedLoginAttemptsPerAddressWithoutBlockingAnotherAddress() {
        var limiter = new LoginRateLimiter(Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC));
        for (int i=0; i<10; i++) limiter.check("192.0.2.1");
        assertThatThrownBy(() -> limiter.check("192.0.2.1"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(429));
        assertThatCode(() -> limiter.check("192.0.2.2")).doesNotThrowAnyException();
    }
}
