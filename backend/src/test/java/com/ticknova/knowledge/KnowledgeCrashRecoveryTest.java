package com.ticknova.knowledge;

import com.sun.net.httpserver.HttpServer;
import com.ticknova.shared.Actor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"ticknova.knowledge.worker-enabled=false","ticknova.knowledge.upload-root=/tmp/ticknova-test-crash"})
@EnabledIfEnvironmentVariable(named="TICKNOVA_INTEGRATION",matches="true")
class KnowledgeCrashRecoveryTest {
    @Autowired JdbcClient jdbc;
    @Autowired KnowledgeService knowledge;

    @Test void aKilledWorkerIsRecoveredByANewJvmAfterItsLeaseExpires() throws Exception {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("ticknova_test");jdbc.sql("TRUNCATE app_user CASCADE").update();
        UUID user=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,display_name,password_hash,must_change_password) VALUES(:id,'crash-editor','Editor','unused',false)").param("id",user).update();
        jdbc.sql("INSERT INTO user_role SELECT :id,id FROM app_role WHERE code='KB_EDITOR'").param("id",user).update();
        var actor=new Actor(user,"crash-editor","Editor",Set.of("KB_EDITOR"),false);UUID space=knowledge.createSpace(actor,"Crash recovery").id();
        var upload=knowledge.upload(actor,space,null,"Crash fixture",new MockMultipartFile("file","guide.txt","text/plain","recovery fixture".getBytes(StandardCharsets.UTF_8)));
        String hash=jdbc.sql("SELECT sha256 FROM document_version WHERE id=:id").param("id",upload.versionId()).query(String.class).single();
        AtomicBoolean permit=new AtomicBoolean(false),entered=new AtomicBoolean(false);
        HttpServer parser=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        parser.createContext("/internal/v1/convert",exchange->{
            exchange.getRequestBody().readAllBytes();entered.set(true);
            try {
                while(!permit.get()){try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}
                byte[] body=("{\"status\":\"SUCCEEDED\",\"documentVersionId\":\""+upload.versionId()+"\",\"sourceSha256\":\""+hash+"\",\"parserVersion\":\"crash-fixture\",\"pages\":[{\"pageNo\":1,\"headingPath\":[],\"text\":\"recovered knowledge\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            } catch(java.io.IOException ignored) { /* The first worker is intentionally killed before its response. */ }
            finally {exchange.close();}
        });parser.start(); Process first=null,second=null;
        try {
            first=launch(parser.getAddress().getPort());Process initial=first;
            await(()->entered.get(),initial);
            UUID oldLease=jdbc.sql("SELECT lease_token FROM outbox_event WHERE job_id=:id").param("id",upload.jobId()).query(UUID.class).single();
            first.destroyForcibly();assertThat(first.waitFor(5,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.sql("SELECT state FROM outbox_event WHERE job_id=:id").param("id",upload.jobId()).query(String.class).single()).isEqualTo("PROCESSING");
            assertThat(knowledge.job(actor,upload.jobId()).status()).isEqualTo("RUNNING");
            // Advance the expired lease state instead of spending two minutes idle in a regression test.
            jdbc.sql("UPDATE outbox_event SET locked_until=now()-interval '1 second' WHERE job_id=:id AND lease_token=:lease").param("id",upload.jobId()).param("lease",oldLease).update();
            permit.set(true);second=launch(parser.getAddress().getPort());Process replacement=second;
            await(()->knowledge.job(actor,upload.jobId()).status().equals("COMPLETED"),replacement);
            assertThat(knowledge.detail(actor,upload.documentId()).latestIngestState()).isEqualTo("READY");
            assertThat(knowledge.job(actor,upload.jobId()).attemptCount()).isEqualTo(2);
            assertThat(knowledge.chunks(actor,upload.documentId(),upload.versionId())).hasSize(1);
            assertThat(jdbc.sql("SELECT state FROM outbox_event WHERE job_id=:id").param("id",upload.jobId()).query(String.class).single()).isEqualTo("DELIVERED");
        } finally {
            permit.set(true);if(first!=null&&first.isAlive()){first.destroyForcibly();first.waitFor(5,TimeUnit.SECONDS);}if(second!=null&&second.isAlive()){second.destroyForcibly();second.waitFor(5,TimeUnit.SECONDS);}parser.stop(0);
        }
    }
    private Process launch(int port) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-cp",System.getProperty("java.class.path"),"com.ticknova.TicknovaApplication",
            "--server.port=0","--spring.main.banner-mode=off","--springdoc.api-docs.enabled=false","--ticknova.knowledge.worker-enabled=true","--ticknova.knowledge.poll-delay-ms=100",
            "--ticknova.knowledge.upload-root=/tmp/ticknova-test-crash","--ticknova.knowledge.docling-base-url=http://127.0.0.1:"+port,"--ticknova.knowledge.docling-token=crash-fixture-internal-token")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    private static void await(BooleanSupplier condition,Process child) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        while(!condition.getAsBoolean()&&System.nanoTime()<until){assertThat(child.isAlive()).as("worker child JVM must remain alive").isTrue();Thread.sleep(50);}
        assertThat(condition.getAsBoolean()).as("worker state must advance before deadline").isTrue();
    }
}
