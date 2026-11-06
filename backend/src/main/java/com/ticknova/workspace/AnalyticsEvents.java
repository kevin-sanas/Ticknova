package com.ticknova.workspace;

import com.ticknova.identity.AuthService;
import com.ticknova.shared.Actor;
import com.ticknova.shared.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Sends only invalidation signals; clients must read the authorized snapshot. */
@Service
public class AnalyticsEvents {
    private static final Logger log=LoggerFactory.getLogger(AnalyticsEvents.class);
    private static final int MAX_SUBSCRIBERS=100;
    private final DataSource dataSource;
    private final AuthService auth;
    private final Object lock=new Object();
    private final ConcurrentHashMap<SseEmitter,Subscriber> subscribers=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor sends=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(256),r -> { var t=new Thread(r,"analytics-sse-send");t.setDaemon(true);return t; },
        new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean running=true;
    private boolean connected;
    private LocalDate utcDay=LocalDate.now(ZoneOffset.UTC);
    private Thread listener;

    public AnalyticsEvents(DataSource dataSource,AuthService auth) { this.dataSource=dataSource;this.auth=auth; }

    @PostConstruct void start() {
        listener=new Thread(this::listen,"analytics-postgres-listener");
        listener.setDaemon(true);listener.start();
    }

    public SseEmitter subscribe(String token) {
        authorize(token);
        var emitter=new SseEmitter(TimeUnit.MINUTES.toMillis(5));
        var subscriber=new Subscriber(token,emitter);
        synchronized(lock) {
            if (!running || !connected) throw new ApiException(503,"ANALYTICS_STREAM_UNAVAILABLE","实时连接暂不可用，请稍后重试");
            if (subscribers.size()>=MAX_SUBSCRIBERS) throw new ApiException(503,"ANALYTICS_STREAM_BUSY","实时连接繁忙，请稍后重试");
            subscribers.put(emitter,subscriber);
        }
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> remove(subscriber));
        emitter.onError(error -> subscribers.remove(emitter));
        queue(subscriber,true);
        return emitter;
    }

    private Actor authorize(String token) {
        Actor actor=auth.authenticate(token);
        if (actor.mustChangePassword()) throw new ApiException(403,"PASSWORD_CHANGE_REQUIRED","请先修改密码");
        actor.requireRole("ADMIN");
        return actor;
    }

    private void listen() {
        long delay=1000;
        while (running) {
            try (Connection connection=dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try {
                    try (var statement=connection.createStatement()) { statement.execute("LISTEN ticknova_analytics"); }
                    PGConnection pg=connection.unwrap(PGConnection.class);
                    synchronized(lock) { if (!running) break;connected=true; }
                    delay=1000;
                    while (running) {
                        var notifications=pg.getNotifications(1000);
                        if (notifications!=null && notifications.length>0) broadcast();
                    }
                } finally {
                    // LISTEN is session scoped: never return a listening connection to the pool.
                    try (var statement=connection.createStatement()) { statement.execute("UNLISTEN ticknova_analytics"); }
                    catch (SQLException ignored) {
                        // Evict the session if it could not be cleaned before returning to the pool.
                        try { connection.abort(Runnable::run); } catch (SQLException ignoredAgain) { }
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                if (running) log.warn("Analytics notifications disconnected; retrying");
            } finally { disconnect(); }
            if (running) {
                try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt();break; }
                delay=Math.min(delay*2,30000);
            }
        }
    }

    private void broadcast() { subscribers.values().forEach(s -> queue(s,true)); }

    @Scheduled(fixedDelay=15000) void heartbeat() {
        // The endpoint's relative ranges move at UTC midnight even without writes.
        LocalDate today=LocalDate.now(ZoneOffset.UTC);
        boolean dayChanged=!today.equals(utcDay);
        utcDay=today;
        subscribers.values().forEach(s -> queue(s,dayChanged));
    }

    private void queue(Subscriber subscriber,boolean refresh) {
        synchronized(subscriber) {
            if (refresh) subscriber.refreshRequested=true;
            if (subscriber.pending) return;
            subscriber.pending=true;
        }
        try {
            sends.execute(() -> {
                while (true) {
                    boolean sendRefresh;
                    synchronized(subscriber) { sendRefresh=subscriber.refreshRequested;subscriber.refreshRequested=false; }
                    try {
                        if (!subscribers.containsKey(subscriber.emitter)) return;
                        authorize(subscriber.token);
                        if (sendRefresh) subscriber.emitter.send(SseEmitter.event().name("refresh").data("{}"));
                        else subscriber.emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (ApiException failure) {
                        try { subscriber.emitter.send(SseEmitter.event().name(failure.status()==401?"auth-expired":"access-revoked").data("{}")); }
                        catch (IOException | IllegalStateException ignored) { }
                        remove(subscriber);return;
                    } catch (IOException | RuntimeException failure) { remove(subscriber);return; }
                    synchronized(subscriber) {
                        if (!subscriber.refreshRequested) { subscriber.pending=false;return; }
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            synchronized(subscriber) { subscriber.pending=false; }
            remove(subscriber);
        }
    }

    private void remove(Subscriber subscriber) {
        if (subscribers.remove(subscriber.emitter)!=null) subscriber.emitter.complete();
    }

    private void disconnect() {
        ArrayList<Subscriber> stale;
        synchronized(lock) { connected=false;stale=new ArrayList<>(subscribers.values()); }
        stale.forEach(this::remove);
    }

    @PreDestroy public void close() {
        running=false;
        if (listener!=null) listener.interrupt();
        disconnect();sends.shutdownNow();
    }

    private static final class Subscriber {
        final String token;
        final SseEmitter emitter;
        boolean pending;
        boolean refreshRequested;
        Subscriber(String token,SseEmitter emitter) { this.token=token;this.emitter=emitter; }
    }
}
