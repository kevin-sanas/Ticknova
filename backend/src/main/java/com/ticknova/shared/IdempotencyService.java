package com.ticknova.shared;

import com.ticknova.shared.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Function;

@Service
public class IdempotencyService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public IdempotencyService(JdbcClient jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
    public record Created<T>(UUID id,T value) {}
    private record Saved(String hash,String state,UUID resource) {}
    private byte[] hash(String text) {
        try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    @Transactional
    public <T> T execute(Actor actor,String operation,String key,Object request,int status,Function<UUID,Created<T>> create,Function<UUID,T> replay) {
        if(key==null||key.isBlank()||key.length()>128)throw new ApiException(400,"IDEMPOTENCY_KEY_REQUIRED","请提供1至128字符的 Idempotency-Key");
        String digest=HexFormat.of().formatHex(hash(json.writeValueAsString(request)));
        long lock=ByteBuffer.wrap(hash(actor.id()+":"+operation+":"+key)).getLong();
        boolean acquired=jdbc.sql("SELECT pg_try_advisory_xact_lock(:lock)").param("lock",lock).query(Boolean.class).single();
        if(!acquired)throw new ApiException(409,"REQUEST_IN_PROGRESS","相同请求正在处理，请稍后重试");
        jdbc.sql("DELETE FROM api_idempotency WHERE user_id=:u AND operation=:op AND idempotency_key=:key AND expires_at<=now()")
            .param("u",actor.id()).param("op",operation).param("key",key).update();
        var saved=jdbc.sql("SELECT request_hash,state,response_resource_id FROM api_idempotency WHERE user_id=:u AND operation=:op AND idempotency_key=:key")
            .param("u",actor.id()).param("op",operation).param("key",key).query((r,n)->new Saved(r.getString(1),r.getString(2),r.getObject(3,UUID.class))).optional();
        if(saved.isPresent()) {
            if(!saved.get().hash.equals(digest))throw new ApiException(409,"IDEMPOTENCY_CONFLICT","同一幂等键已用于不同内容");
            if(!saved.get().state.equals("COMPLETED"))throw new ApiException(409,"REQUEST_IN_PROGRESS","相同请求正在处理，请稍后重试");
            return replay.apply(saved.get().resource);
        }
        UUID requestId=UUID.randomUUID();
        jdbc.sql("INSERT INTO api_idempotency(id,user_id,operation,idempotency_key,request_hash,state,expires_at) VALUES(:id,:u,:op,:key,:hash,'IN_PROGRESS',now()+interval '24 hours')")
            .param("id",requestId).param("u",actor.id()).param("op",operation).param("key",key).param("hash",digest).update();
        Created<T> result=create.apply(requestId);
        jdbc.sql("UPDATE api_idempotency SET state='COMPLETED',response_status=:status,response_resource_id=:resource WHERE id=:id")
            .param("status",status).param("resource",result.id).param("id",requestId).update();
        return result.value;
    }
    @Scheduled(fixedDelay=600_000)
    public void expire() {jdbc.sql("DELETE FROM api_idempotency WHERE expires_at<now()").update();}
}
