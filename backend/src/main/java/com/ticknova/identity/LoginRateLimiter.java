package com.ticknova.identity;

import com.ticknova.shared.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Small bounded fixed-window limiter; keys use the socket peer, never client supplied forwarding headers. */
@Component
public class LoginRateLimiter {
    private static final long WINDOW_MILLIS=Duration.ofMinutes(15).toMillis();
    private final Clock clock;
    private final LinkedHashMap<String,Bucket> buckets=new LinkedHashMap<>();

    public LoginRateLimiter() { this(Clock.systemUTC()); }
    LoginRateLimiter(Clock clock) { this.clock=clock; }

    public synchronized void check(String peer) {
        long now=clock.millis();
        buckets.entrySet().removeIf(e -> now-e.getValue().startedAt>=WINDOW_MILLIS);
        Bucket bucket=buckets.get(peer);
        if (bucket==null) {
            if (buckets.size()>=10_000) buckets.remove(buckets.keySet().iterator().next());
            bucket=new Bucket(now); buckets.put(peer,bucket);
        }
        if (bucket.attempts>=10) throw new ApiException(429,"LOGIN_RATE_LIMITED","登录尝试过多，请稍后重试");
        bucket.attempts++;
    }

    private static final class Bucket {
        final long startedAt; int attempts;
        Bucket(long startedAt) { this.startedAt=startedAt; }
    }
}
