package com.ticknova.workspace;

import com.ticknova.identity.SessionTokens;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Run only against a disposable, migrated ticknova_test database. Never truncates tables. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"ticknova.ai.worker-enabled=false","ticknova.knowledge.worker-enabled=false"})
@EnabledIfEnvironmentVariable(named="TICKNOVA_ISOLATED_STREAM_TEST",matches="true")
class AnalyticsEventsPostgresIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource data;
    @Autowired SessionTokens tokens;
    @Autowired ObjectMapper json;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test void committedChangesRefreshAuthorizedStreamAndRollbackDoesNot() throws Exception {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");
        UUID admin=UUID.randomUUID(), requester=UUID.randomUUID(), ticket=UUID.randomUUID();
        String adminToken=null,requesterToken=null;
        InputStream body=null;
        Logger root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var logs=new ListAppender<ILoggingEvent>();logs.start();root.addAppender(logs);
        try {
            adminToken=user(admin,"ADMIN");requesterToken=user(requester,"REQUESTER");
            var anonymous=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/workspace/analytics/events")).GET().build();
            assertThat(http.send(anonymous,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
            assertThat(open(requesterToken).statusCode()).isEqualTo(403);
            HttpResponse<InputStream> stream=null;
            for(int i=0;i<30;i++) {
                stream=open(adminToken);
                if(stream.statusCode()==200)break;
                stream.body().close();Thread.sleep(100);
            }
            assertThat(stream).isNotNull();assertThat(stream.statusCode()).isEqualTo(200);
            body=stream.body();var lines=new LinkedBlockingQueue<String>();InputStream input=body;
            Thread reader=new Thread(() -> { try (var r=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
                String line;while((line=r.readLine())!=null)if(line.startsWith("event:"))lines.offer(line.substring(6).trim());
            } catch(IOException ignored) { } });reader.setDaemon(true);reader.start();
            assertThat(lines.poll(5,TimeUnit.SECONDS)).isEqualTo("refresh");
            long before=total(adminToken);
            try(Connection c=data.getConnection()) {
                c.setAutoCommit(false);insertTicket(c,ticket,requester);c.rollback();
            }
            assertThat(lines.poll(1,TimeUnit.SECONDS)).isNull();
            try(Connection c=data.getConnection()) {
                c.setAutoCommit(false);insertTicket(c,ticket,requester);c.commit();
            }
            assertThat(lines.poll(5,TimeUnit.SECONDS)).isEqualTo("refresh");
            assertThat(total(adminToken)).isEqualTo(before+1);
            jdbc.sql("UPDATE user_session SET revoked_at=now() WHERE user_id=:id").param("id",admin).update();
            jdbc.sql("UPDATE ticket SET title='changed' WHERE id=:id").param("id",ticket).update();
            assertThat(lines.poll(5,TimeUnit.SECONDS)).isEqualTo("auth-expired");
            reader.join(5000);
            assertThat(reader.isAlive()).as("revoked stream terminates").isFalse();
            assertThat(logs.list).noneMatch(event -> event.getThrowableProxy()!=null
                && event.getThrowableProxy().getClassName().equals("org.springframework.security.authorization.AuthorizationDeniedException"));
        } finally {
            if(body!=null)body.close();
            jdbc.sql("DELETE FROM ticket WHERE id=:id").param("id",ticket).update();
            jdbc.sql("DELETE FROM user_session WHERE user_id IN (:a,:r)").param("a",admin).param("r",requester).update();
            jdbc.sql("DELETE FROM user_role WHERE user_id IN (:a,:r)").param("a",admin).param("r",requester).update();
            jdbc.sql("DELETE FROM app_user WHERE id IN (:a,:r)").param("a",admin).param("r",requester).update();
            root.detachAppender(logs);logs.stop();
        }
    }
    private String user(UUID id,String role) {
        jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,:n,'test','unused',false)").param("id",id).param("n","stream-"+id).update();
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code=:role").param("id",id).param("role",role).update();
        var token=tokens.generate();
        jdbc.sql("INSERT INTO user_session(id,user_id,token_hash,expires_at) VALUES(:id,:user,:hash,now()+interval '30 minutes')").param("id",UUID.randomUUID()).param("user",id).param("hash",token.hash()).update();
        return token.token();
    }
    private void insertTicket(Connection c,UUID id,UUID user) throws Exception {
        try(var q=c.prepareStatement("INSERT INTO ticket(id,title,description,requester_id,queue_id) VALUES(?, 'stream fixture','fixture',?, '20000000-0000-0000-0000-000000000001')")) {
            q.setObject(1,id);q.setObject(2,user);q.executeUpdate();
        }
    }
    private HttpResponse<InputStream> open(String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/workspace/analytics/events")).header("Authorization","Bearer "+token).GET().build();
        return http.send(request,HttpResponse.BodyHandlers.ofInputStream());
    }
    private long total(String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/workspace/analytics")).header("Authorization","Bearer "+token).GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("ticketTotal").asLong();
    }
}
