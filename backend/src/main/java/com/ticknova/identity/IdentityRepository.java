package com.ticknova.identity;

import com.ticknova.shared.Actor;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IdentityRepository {
    private final JdbcClient jdbc;
    public IdentityRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    public Optional<Account> findByUsername(String username) {
        return jdbc.sql("SELECT id,username,display_name,active,must_change_password,password_hash FROM app_user WHERE username=:username")
            .param("username",username).query((rs,n)->new Account(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),rs.getBoolean("active"),rs.getBoolean("must_change_password"),rs.getString("password_hash"))).optional();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT id,username,display_name,active,must_change_password,password_hash FROM app_user WHERE id=:id")
            .param("id",id).query((rs,n)->new Account(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),rs.getBoolean("active"),rs.getBoolean("must_change_password"),rs.getString("password_hash"))).optional();
    }

    public Set<String> roles(UUID id) {
        return Set.copyOf(jdbc.sql("SELECT r.code FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=:id ORDER BY r.code")
            .param("id",id).query(String.class).list());
    }

    @Transactional
    public boolean createSessionIfPasswordCurrent(UUID userId,String expectedHash,String hash,Instant expiresAt) {
        // Share the user row lock taken by password/active updates, then recheck the verified credential.
        // A login verified before a password change cannot commit a session after that change revoked sessions.
        var current=jdbc.sql("SELECT password_hash FROM app_user WHERE id=:id AND active FOR UPDATE")
            .param("id",userId).query(String.class).optional();
        if (current.isEmpty() || !current.get().equals(expectedHash)) return false;
        jdbc.sql("INSERT INTO user_session(id,user_id,token_hash,expires_at) VALUES(:id,:user,:hash,:expires)")
            .param("id",UUID.randomUUID()).param("user",userId).param("hash",hash).param("expires",java.sql.Timestamp.from(expiresAt)).update();
        return true;
    }

    public Optional<Actor> sessionActor(String hash) {
        return jdbc.sql("""
            SELECT u.id,u.username,u.display_name,u.must_change_password,
              COALESCE((SELECT array_agg(r.code ORDER BY r.code) FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=u.id),ARRAY[]::varchar[]) role_codes
            FROM user_session s JOIN app_user u ON u.id=s.user_id
             WHERE s.token_hash=:hash AND s.revoked_at IS NULL AND s.expires_at>now() AND u.active
            """).param("hash",hash).query((rs,n)->new Actor(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),Set.copyOf(Arrays.asList((String[])rs.getArray("role_codes").getArray())),rs.getBoolean("must_change_password"))).optional();
    }

    public void revokeSession(UUID userId,String hash) {
        jdbc.sql("UPDATE user_session SET revoked_at=now() WHERE user_id=:user AND token_hash=:hash AND revoked_at IS NULL")
            .param("user",userId).param("hash",hash).update();
    }

    public boolean changePassword(UUID userId,String previousHash,String nextHash) {
        // Compare-and-set prevents two concurrent password changes from both accepting the same old password.
        int changed=jdbc.sql("UPDATE app_user SET password_hash=:next,must_change_password=false WHERE id=:id AND password_hash=:old AND active")
            .param("id",userId).param("next",nextHash).param("old",previousHash).update();
        if (changed==0) return false;
        jdbc.sql("UPDATE user_session SET revoked_at=now() WHERE user_id=:id AND revoked_at IS NULL").param("id",userId).update();
        return true;
    }

    public record Account(UUID id,String username,String displayName,boolean active,boolean mustChangePassword,String passwordHash) {}
}
